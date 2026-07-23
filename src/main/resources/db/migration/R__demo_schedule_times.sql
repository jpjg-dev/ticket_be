-- 데모 공연/회차 시각을 배포 시점(now) 기준으로 재적용하는 repeatable 마이그레이션.
-- versioned(V5/V7/V8/V9)로 데모 시각을 갱신하면 손볼 때마다 버전 번호가 누적된다.
-- repeatable은 파일 체크섬이 바뀌면 재실행되므로, 이 파일을 고쳐 재빌드/재배포하면
-- 그 시점 now() 기준으로 데모 시각이 다시 깔린다(버전 번호 누적 없음).
-- refresh marker: 2026-07-23
--
-- 규칙(V9와 동일, DataInitializer와 항상 같은 offset 유지):
--   base = date_trunc('day', now KST). 공연별 '연속 런'(인접 날짜/같은 날 다른 시각),
--   공연들끼리는 시작 시기를 다양하게 둔다. 오페라는 이미 시작한 런(첫 회차 과거 → 필터 시연).
--
-- 주의:
--   - 빈(fresh) DB에서는 Flyway 실행 시점에 schedules가 비어 있어 UPDATE가 0 row다.
--     빈 DB의 데모 시각은 DataInitializer가 채운다. 이 파일은 이미 시드된 기존 DB 한정으로 동작한다.
--   - jar에 구워지므로 단순 reload로는 반영되지 않는다. 재빌드+재배포로 체크섬이 바뀌어야 재실행된다.

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

-- 2) 공연 회차: 공연별 연속 런. base = KST 자정 + 자연 시각.
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
