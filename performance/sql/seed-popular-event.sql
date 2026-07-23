INSERT INTO events (id, booking_open_at, created_at, venue, title, description)
VALUES (
    8,
    NOW() - INTERVAL '1 day',
    NOW(),
    'Performance Test Hall',
    'PERF_LOAD_TEST_EVENT',
    'Popular event E2E load-test fixture'
)
ON CONFLICT (id) DO UPDATE
SET booking_open_at = EXCLUDED.booking_open_at;

INSERT INTO schedules (id, created_at, start_at, end_at, event_id)
VALUES (
    18,
    NOW(),
    NOW() + INTERVAL '30 days',
    NOW() + INTERVAL '30 days 2 hours 30 minutes',
    8
)
ON CONFLICT (id) DO UPDATE
SET start_at = EXCLUDED.start_at,
    end_at = EXCLUDED.end_at;

INSERT INTO seats (id, price, created_at, schedule_id, grade, seat_number, status)
SELECT
    390 + sequence,
    1000,
    NOW(),
    18,
    'R',
    'LOAD-' || LPAD(sequence::text, 4, '0'),
    'AVAILABLE'
FROM generate_series(1, 1000) AS sequence
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('events', 'id'), (SELECT MAX(id) FROM events));
SELECT setval(pg_get_serial_sequence('schedules', 'id'), (SELECT MAX(id) FROM schedules));
SELECT setval(pg_get_serial_sequence('seats', 'id'), (SELECT MAX(id) FROM seats));

SELECT
    schedules.id AS schedule_id,
    COUNT(seats.id) AS total_load_seats,
    MIN(seats.id) AS min_seat_id,
    MAX(seats.id) AS max_seat_id
FROM schedules
JOIN seats ON seats.schedule_id = schedules.id
WHERE schedules.id = 18
GROUP BY schedules.id;
