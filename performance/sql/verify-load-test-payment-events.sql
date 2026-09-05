-- Run after requests and event delivery settle, before resetting the fixture.
-- Default k6 fixture: schedule 18, seat IDs 391 through 1390.
WITH target_seats AS (
    SELECT s.* FROM seats s
    JOIN schedules sc ON sc.id = s.schedule_id
    JOIN events e ON e.id = sc.event_id
    WHERE e.title = 'PERF_LOAD_TEST_EVENT' AND s.seat_number LIKE 'LOAD-%'
      AND s.schedule_id = 18 AND s.id BETWEEN 391 AND 1390
), target_payments AS (
    SELECT DISTINCT p.* FROM payments p
    JOIN reservations r ON r.reservation_group_id = p.reservation_group_id
    JOIN target_seats s ON s.id = r.seat_id
), approved AS (
    SELECT * FROM target_payments WHERE status = 'APPROVED'
), coverage AS (
    SELECT p.id,
           (SELECT COUNT(*) FROM payment_outbox_events o
            WHERE o.payment_id = p.id AND o.event_type = 'PaymentApproved') AS event_count,
           (SELECT COUNT(*) FROM payment_outbox_events o
            JOIN payment_event_inbox i ON i.event_id = o.event_id
            JOIN payment_event_audit a ON a.event_id = o.event_id
            WHERE o.payment_id = p.id AND o.event_type = 'PaymentApproved'
              AND o.status = 'PUBLISHED' AND o.payload_hash IS NOT NULL
              AND i.payload_hash IS NOT DISTINCT FROM o.payload_hash
              AND a.payload_hash IS NOT DISTINCT FROM o.payload_hash) AS delivered_count
    FROM approved p
)
SELECT (SELECT COUNT(*) FROM target_seats) AS total_seats,
       (SELECT COUNT(*) FROM approved) AS approved_payments,
       (SELECT COUNT(*) FROM target_seats WHERE status = 'BOOKED') AS booked_seats,
       (SELECT COUNT(*) FROM coverage WHERE event_count <> 1 OR delivered_count <> 1) AS event_coverage_errors,
       (SELECT COUNT(*) FROM approved p
        JOIN reservation_groups g ON g.id = p.reservation_group_id
        WHERE g.status <> 'CONFIRMED'
           OR EXISTS (SELECT 1 FROM reservations r JOIN seats s ON s.id = r.seat_id
                      WHERE r.reservation_group_id = g.id
                        AND (r.status <> 'CONFIRMED' OR s.status <> 'BOOKED'))) AS approved_payment_state_mismatches,
       (SELECT COUNT(*) FROM target_seats s WHERE s.status = 'BOOKED'
        AND NOT EXISTS (
            SELECT 1 FROM reservations r JOIN approved p ON p.reservation_group_id = r.reservation_group_id
            WHERE r.seat_id = s.id AND r.status = 'CONFIRMED'
        )) AS booked_without_approved_payment,
       (SELECT COUNT(*) FROM reservations r JOIN target_seats s ON s.id = r.seat_id
        WHERE r.status = 'CONFIRMED' AND NOT EXISTS (
            SELECT 1 FROM approved p WHERE p.reservation_group_id = r.reservation_group_id
        )) AS confirmed_without_approved_payment,
       (SELECT COUNT(*) FROM (
           SELECT r.seat_id FROM reservations r JOIN target_seats s ON s.id = r.seat_id
           WHERE r.status IN ('PENDING', 'CONFIRMED')
           GROUP BY r.seat_id HAVING COUNT(*) > 1
       ) duplicates) AS duplicate_active_seats;
