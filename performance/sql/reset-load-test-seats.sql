BEGIN;

WITH target_schedule AS (
    SELECT s.id
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title = 'PERF_LOAD_TEST_EVENT'
),
target_seats AS (
    SELECT seat.id
    FROM seats seat
    JOIN target_schedule schedule ON schedule.id = seat.schedule_id
    WHERE seat.seat_number LIKE 'LOAD-%'
),
target_groups AS (
    SELECT DISTINCT reservation.reservation_group_id AS id
    FROM reservations reservation
    JOIN target_seats seat ON seat.id = reservation.seat_id
    WHERE reservation.reservation_group_id IS NOT NULL
)
DELETE FROM payments payment
USING target_groups reservation_group
WHERE payment.reservation_group_id = reservation_group.id;

WITH target_schedule AS (
    SELECT s.id
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title = 'PERF_LOAD_TEST_EVENT'
),
target_seats AS (
    SELECT seat.id
    FROM seats seat
    JOIN target_schedule schedule ON schedule.id = seat.schedule_id
    WHERE seat.seat_number LIKE 'LOAD-%'
)
DELETE FROM reservations reservation
USING target_seats seat
WHERE reservation.seat_id = seat.id;

WITH target_schedule AS (
    SELECT s.id
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title = 'PERF_LOAD_TEST_EVENT'
),
orphan_target_groups AS (
    SELECT reservation_group.id
    FROM reservation_groups reservation_group
    WHERE NOT EXISTS (
        SELECT 1
        FROM reservations reservation
        WHERE reservation.reservation_group_id = reservation_group.id
    )
      AND EXISTS (
        SELECT 1
        FROM target_schedule
    )
)
DELETE FROM reservation_groups reservation_group
USING orphan_target_groups target
WHERE reservation_group.id = target.id;

WITH target_schedule AS (
    SELECT s.id
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title = 'PERF_LOAD_TEST_EVENT'
)
UPDATE seats seat
SET status = 'AVAILABLE'
FROM target_schedule schedule
WHERE seat.schedule_id = schedule.id
  AND seat.seat_number LIKE 'LOAD-%';

COMMIT;

SELECT
    s.id AS schedule_id,
    COUNT(seat.id) AS total_load_seats,
    COUNT(*) FILTER (WHERE seat.status = 'AVAILABLE') AS available_load_seats,
    MIN(seat.id) AS min_seat_id,
    MAX(seat.id) AS max_seat_id
FROM schedules s
JOIN events e ON e.id = s.event_id
JOIN seats seat ON seat.schedule_id = s.id
WHERE e.title = 'PERF_LOAD_TEST_EVENT'
  AND seat.seat_number LIKE 'LOAD-%'
GROUP BY s.id
ORDER BY s.id;
