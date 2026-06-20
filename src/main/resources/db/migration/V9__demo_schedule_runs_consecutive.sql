-- 데모 회차를 실제 공연처럼 '연속 런'으로 묶는다.
-- V8은 한 공연의 회차가 수개월씩 흩어져 비현실적이었다(예: -3일/+5일/+40일).
-- 실제 공연은 같은 시기에 며칠 연속(예: 6/25, 6/26) 또는 같은 날 다른 시각(예: 15:00, 19:00)으로 진행된다.
-- 공연별로 회차를 인접 날짜/시각으로 묶고, 공연들끼리는 시작 시기를 다양하게 둔다.
-- 배포 시점 기준 상대 분배 + 자연 시각(V8 규칙 유지). base = date_trunc('day', now KST).

-- 1) 예매 오픈(절대시각, now 기준). 5개 오픈(과거) + 2개 오픈 예정(미래).
WITH booking_off(title, day_off) AS (
    VALUES
        ('오페라의 유령', -25),
        ('레미제라블',   -12),
        ('위키드',        -8),
        ('시카고',        -6),
        ('마타하리',      -4),
        ('하데스타운',     2),
        ('킹키부츠',       5)
)
UPDATE events e
SET booking_open_at = now() + make_interval(days => booking_off.day_off)
FROM booking_off
WHERE e.title = booking_off.title;

-- 2) 공연 회차: 공연별 연속 런. 오페라는 이미 시작한 런(첫 회차 과거 → 필터 시연).
WITH base AS (
    SELECT date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AS d
),
ranked AS (
    SELECT s.id,
           e.title,
           row_number() OVER (PARTITION BY e.title ORDER BY s.id) AS ord
    FROM schedules s
    JOIN events e ON e.id = s.event_id
    WHERE e.title IN (
        '오페라의 유령', '레미제라블', '위키드', '시카고', '마타하리', '하데스타운', '킹키부츠'
    )
),
sched(title, ord, day_off, hh, mm) AS (
    VALUES
        ('오페라의 유령', 1, -1, 19, 0),   -- 지난 회차(필터 시연)
        ('오페라의 유령', 2,  1, 19, 0),
        ('오페라의 유령', 3,  2, 14, 0),
        ('레미제라블', 1, 3, 19, 0),
        ('레미제라블', 2, 4, 15, 0),       -- 같은 날 마티네
        ('레미제라블', 3, 4, 19, 0),       -- 같은 날 저녁
        ('위키드', 1, 6, 20, 0),
        ('위키드', 2, 7, 19, 0),
        ('시카고', 1, 10, 15, 0),          -- 같은 날 마티네
        ('시카고', 2, 10, 19, 0),          -- 같은 날 저녁
        ('마타하리', 1, 15, 19, 0),
        ('마타하리', 2, 16, 19, 0),
        ('하데스타운', 1, 25, 19, 0),
        ('하데스타운', 2, 26, 14, 0),
        ('킹키부츠', 1, 40, 19, 0),
        ('킹키부츠', 2, 41, 15, 0)
)
UPDATE schedules s
SET start_at = base.d + make_interval(days => sched.day_off, hours => sched.hh, mins => sched.mm),
    end_at   = base.d + make_interval(days => sched.day_off, hours => sched.hh, mins => sched.mm) + INTERVAL '2 hours 30 minutes'
FROM base, ranked, sched
WHERE s.id = ranked.id
  AND ranked.title = sched.title
  AND ranked.ord = sched.ord;
