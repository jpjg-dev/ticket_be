-- 데모 데이터의 공연/예매 시각을 '배포 시점(now) 기준 상대 분배'로 전환한다.
-- V7(고정 절대날짜)은 시간이 지나면 모든 회차가 과거로 몰려 메인이 부자연스러워지는 문제가 있었다.
-- 배포일 기준으로 근시일~수개월에 걸쳐 자연스럽게 재분배한다.
-- V5의 date_trunc('hour') 버그(06:00 같은 비현실적 시각)를 피하려고 base는 date_trunc('day', KST)로 잡고 시각을 명시한다.
--   start_at/end_at : 공연장 KST 벽시계 = date_trunc('day', now KST) + N일 + 고정 시각
--   booking_open_at : UTC 절대시각 = now() ± N일

-- 1) 예매 오픈(절대시각, now 기준). 5개는 이미 오픈(과거), 2개는 오픈 예정(미래).
WITH booking_off(title, day_off) AS (
    VALUES
        ('오페라의 유령', -20),
        ('레미제라블',   -15),
        ('위키드',       -10),
        ('시카고',        -7),
        ('마타하리',      -5),
        ('하데스타운',     3),
        ('킹키부츠',       7)
)
UPDATE events e
SET booking_open_at = now() + make_interval(days => booking_off.day_off)
FROM booking_off
WHERE e.title = booking_off.title;

-- 2) 공연 회차(KST 벽시계, 배포일 기준 상대 + 자연 시각). 회차 순서는 삽입 순서(id)로 매핑한다.
--    각 공연은 과거 일부 + 근시일 + 수개월 후로 분산해 '지난 회차 제외' 필터와 '곧 시작' 섹션이 모두 자연스럽게 보이도록 했다.
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
        ('오페라의 유령', 1, -3,  19, 0),   -- 지남(필터 시연)
        ('오페라의 유령', 2,  5,  19, 0),
        ('오페라의 유령', 3, 40,  15, 0),
        ('레미제라블', 1,  2, 19, 0),
        ('레미제라블', 2, 18, 19, 0),
        ('레미제라블', 3, 60, 14, 0),
        ('위키드', 1,  7, 20, 0),
        ('위키드', 2, 35, 19, 0),
        ('시카고', 1, 10, 15, 0),
        ('시카고', 2, 50, 19, 0),
        ('마타하리', 1, 14, 20, 0),
        ('마타하리', 2, 75, 14, 0),
        ('하데스타운', 1, 21, 19, 0),
        ('하데스타운', 2, 95, 15, 0),
        ('킹키부츠', 1, 28, 19, 0),
        ('킹키부츠', 2, 130, 14, 0)
)
UPDATE schedules s
SET start_at = base.d + make_interval(days => sched.day_off, hours => sched.hh, mins => sched.mm),
    end_at   = base.d + make_interval(days => sched.day_off, hours => sched.hh, mins => sched.mm) + INTERVAL '2 hours 30 minutes'
FROM base, ranked, sched
WHERE s.id = ranked.id
  AND ranked.title = sched.title
  AND ranked.ord = sched.ord;
