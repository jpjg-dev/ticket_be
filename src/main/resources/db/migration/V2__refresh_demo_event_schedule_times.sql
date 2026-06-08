WITH base_time AS (
    SELECT date_trunc('hour', CURRENT_TIMESTAMP)::timestamp AS base
),
event_time_updates(title, booking_open_offset) AS (
    VALUES
        ('오페라의 유령', INTERVAL '-7 days'),
        ('레미제라블', INTERVAL '-6 days'),
        ('위키드', INTERVAL '-5 days'),
        ('시카고', INTERVAL '-4 days'),
        ('마타하리', INTERVAL '-3 days'),
        ('하데스타운', INTERVAL '-2 days'),
        ('킹키부츠', INTERVAL '-1 day')
)
UPDATE events e
SET booking_open_at = base_time.base + event_time_updates.booking_open_offset
FROM base_time, event_time_updates
WHERE e.title = event_time_updates.title;

WITH base_time AS (
    SELECT date_trunc('hour', CURRENT_TIMESTAMP)::timestamp AS base
),
ranked_schedules AS (
    SELECT
        s.id,
        e.title,
        row_number() OVER (PARTITION BY e.title ORDER BY s.start_at, s.id) AS schedule_order
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title IN (
        '오페라의 유령',
        '레미제라블',
        '위키드',
        '시카고',
        '마타하리',
        '하데스타운',
        '킹키부츠'
    )
),
schedule_time_updates(title, schedule_order, start_offset) AS (
    VALUES
        ('오페라의 유령', 1, INTERVAL '1 day 19 hours'),
        ('오페라의 유령', 2, INTERVAL '2 days 14 hours'),
        ('오페라의 유령', 3, INTERVAL '3 days 18 hours'),
        ('레미제라블', 1, INTERVAL '4 days 19 hours'),
        ('레미제라블', 2, INTERVAL '5 days 15 hours'),
        ('레미제라블', 3, INTERVAL '6 days 19 hours'),
        ('위키드', 1, INTERVAL '7 days 20 hours'),
        ('위키드', 2, INTERVAL '8 days 19 hours'),
        ('시카고', 1, INTERVAL '9 days 19 hours'),
        ('시카고', 2, INTERVAL '10 days 15 hours'),
        ('마타하리', 1, INTERVAL '11 days 20 hours'),
        ('마타하리', 2, INTERVAL '12 days 14 hours'),
        ('하데스타운', 1, INTERVAL '13 days 19 hours'),
        ('하데스타운', 2, INTERVAL '14 days 15 hours'),
        ('킹키부츠', 1, INTERVAL '15 days 19 hours'),
        ('킹키부츠', 2, INTERVAL '16 days 14 hours')
)
UPDATE schedules s
SET
    start_at = base_time.base + schedule_time_updates.start_offset,
    end_at = base_time.base + schedule_time_updates.start_offset + INTERVAL '2 hours 30 minutes'
FROM base_time, ranked_schedules, schedule_time_updates
WHERE s.id = ranked_schedules.id
  AND ranked_schedules.title = schedule_time_updates.title
  AND ranked_schedules.schedule_order = schedule_time_updates.schedule_order;
