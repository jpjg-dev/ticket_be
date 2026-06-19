-- 데모 데이터의 예매 오픈/공연 시각이 시간이 지나 모두 과거가 되므로, 배포 시점(now) 기준으로 다시 분배한다.
-- V4 에서 타입을 바꾼 뒤 실행된다.
--   booking_open_at : 절대 시점(Instant) → now() 기준 (timestamptz)
--   start_at/end_at : 공연장 로컬 벽시계(KST) → (now() AT TIME ZONE 'Asia/Seoul') 기준 (timestamp without time zone)

-- 1) 예매 오픈: 5개는 이미 오픈(과거), 2개는 오픈 예정(미래)으로 둔다.
WITH booking_offsets(title, open_offset) AS (
    VALUES
        ('오페라의 유령', INTERVAL '-9 days'),
        ('레미제라블', INTERVAL '-8 days'),
        ('위키드', INTERVAL '-7 days'),
        ('시카고', INTERVAL '-6 days'),
        ('마타하리', INTERVAL '-5 days'),
        ('하데스타운', INTERVAL '2 days'),
        ('킹키부츠', INTERVAL '5 days')
)
UPDATE events e
SET booking_open_at = now() + booking_offsets.open_offset
FROM booking_offsets
WHERE e.title = booking_offsets.title;

-- 2) 공연 회차: 종료/진행중/예정이 섞이도록 KST 벽시계 기준으로 분배한다.
WITH base_time AS (
    SELECT date_trunc('hour', now() AT TIME ZONE 'Asia/Seoul') AS base
),
ranked_schedules AS (
    SELECT
        s.id,
        e.title,
        row_number() OVER (PARTITION BY e.title ORDER BY s.start_at, s.id) AS schedule_order
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title IN (
        '오페라의 유령', '레미제라블', '위키드', '시카고', '마타하리', '하데스타운', '킹키부츠'
    )
),
schedule_offsets(title, schedule_order, start_offset) AS (
    VALUES
        ('오페라의 유령', 1, INTERVAL '-5 days 19 hours'),   -- 종료
        ('오페라의 유령', 2, INTERVAL '-1 hours'),           -- 진행중
        ('오페라의 유령', 3, INTERVAL '2 days 19 hours'),    -- 예정
        ('레미제라블', 1, INTERVAL '-3 days 19 hours'),      -- 종료
        ('레미제라블', 2, INTERVAL '1 days 19 hours'),
        ('레미제라블', 3, INTERVAL '4 days 15 hours'),
        ('위키드', 1, INTERVAL '-30 minutes'),               -- 진행중
        ('위키드', 2, INTERVAL '6 days 19 hours'),
        ('시카고', 1, INTERVAL '1 days 15 hours'),
        ('시카고', 2, INTERVAL '3 days 19 hours'),
        ('마타하리', 1, INTERVAL '2 days 20 hours'),
        ('마타하리', 2, INTERVAL '5 days 14 hours'),
        ('하데스타운', 1, INTERVAL '7 days 19 hours'),
        ('하데스타운', 2, INTERVAL '9 days 15 hours'),
        ('킹키부츠', 1, INTERVAL '10 days 19 hours'),
        ('킹키부츠', 2, INTERVAL '12 days 14 hours')
)
UPDATE schedules s
SET
    start_at = base_time.base + schedule_offsets.start_offset,
    end_at = base_time.base + schedule_offsets.start_offset + INTERVAL '2 hours 30 minutes'
FROM base_time, ranked_schedules, schedule_offsets
WHERE s.id = ranked_schedules.id
  AND ranked_schedules.title = schedule_offsets.title
  AND ranked_schedules.schedule_order = schedule_offsets.schedule_order;
