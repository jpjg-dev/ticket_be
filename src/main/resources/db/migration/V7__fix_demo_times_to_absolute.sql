-- 데모 데이터의 예매 오픈/공연 시각을 '상대 offset 분배'에서 '고정 절대값'으로 단순화한다.
-- 배경: V5 는 base = date_trunc('hour', now() AT TIME ZONE 'Asia/Seoul') + INTERVAL 'N hours' 조합이라
--       base 에 현재 '시(時)'가 남아 06:00 같은 비현실적 showtime 이 생겼다. offset 조합 자체를 제거한다.
--   start_at/end_at : 공연장 KST 벽시계 고정 (TIMESTAMP WITHOUT TIME ZONE, 변환 없이 그대로)
--   booking_open_at : UTC 절대시각 고정 (TIMESTAMPTZ, 저장은 UTC·표시 단계에서만 KST 변환)
-- 주의: 고정값이므로 시간이 지나면 과거 회차가 늘어난다. 시연 전 날짜 조정(후속 마이그레이션)이 필요할 수 있다.
--       각 공연은 과거 회차 일부 + 미래 회차 다수로 구성해 '지난 회차 제외' 필터가 계속 보이도록 했다.

-- 1) 예매 오픈: 절대시각(UTC) 고정. 5개는 이미 오픈(과거), 2개는 오픈 예정(미래).
WITH booking_fixed(title, open_at) AS (
    VALUES
        ('오페라의 유령', '2026-05-20 01:00:00+00'::timestamptz),
        ('레미제라블',   '2026-05-25 01:00:00+00'::timestamptz),
        ('위키드',       '2026-06-01 01:00:00+00'::timestamptz),
        ('시카고',       '2026-06-10 01:00:00+00'::timestamptz),
        ('마타하리',     '2026-06-15 01:00:00+00'::timestamptz),
        ('하데스타운',   '2026-07-05 01:00:00+00'::timestamptz),
        ('킹키부츠',     '2026-07-20 01:00:00+00'::timestamptz)
)
UPDATE events e
SET booking_open_at = booking_fixed.open_at
FROM booking_fixed
WHERE e.title = booking_fixed.title;

-- 2) 공연 회차: KST 벽시계 고정 (end_at = start_at + 2시간 30분). 회차 순서는 삽입 순서(id)로 매핑한다.
WITH ranked AS (
    SELECT s.id,
           e.title,
           row_number() OVER (PARTITION BY e.title ORDER BY s.id) AS ord
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title IN (
        '오페라의 유령', '레미제라블', '위키드', '시카고', '마타하리', '하데스타운', '킹키부츠'
    )
),
schedule_fixed(title, ord, start_at) AS (
    VALUES
        ('오페라의 유령', 1, '2026-06-10 19:00:00'::timestamp),  -- 지남
        ('오페라의 유령', 2, '2026-08-15 19:00:00'::timestamp),
        ('오페라의 유령', 3, '2026-09-05 15:00:00'::timestamp),
        ('레미제라블', 1, '2026-06-13 19:00:00'::timestamp),     -- 지남
        ('레미제라블', 2, '2026-09-20 19:00:00'::timestamp),
        ('레미제라블', 3, '2026-11-15 14:00:00'::timestamp),
        ('위키드', 1, '2026-06-15 20:00:00'::timestamp),         -- 지남
        ('위키드', 2, '2026-10-03 19:00:00'::timestamp),
        ('시카고', 1, '2026-08-22 15:00:00'::timestamp),
        ('시카고', 2, '2026-10-10 19:00:00'::timestamp),
        ('마타하리', 1, '2026-09-12 20:00:00'::timestamp),
        ('마타하리', 2, '2026-11-28 14:00:00'::timestamp),
        ('하데스타운', 1, '2026-08-30 19:00:00'::timestamp),
        ('하데스타운', 2, '2026-12-05 15:00:00'::timestamp),
        ('킹키부츠', 1, '2026-10-18 19:00:00'::timestamp),
        ('킹키부츠', 2, '2027-01-10 14:00:00'::timestamp)
)
UPDATE schedules s
SET start_at = schedule_fixed.start_at,
    end_at   = schedule_fixed.start_at + INTERVAL '2 hours 30 minutes'
FROM ranked, schedule_fixed
WHERE s.id = ranked.id
  AND ranked.title = schedule_fixed.title
  AND ranked.ord = schedule_fixed.ord;
