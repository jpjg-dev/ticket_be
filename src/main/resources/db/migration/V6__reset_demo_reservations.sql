-- 데모 예약/결제 데이터를 초기화한다(취소·만료·확정 전부).
-- 계정(users)·리프레시 토큰·공연 카탈로그(events/schedules/seats)는 유지한다.
-- 기존(운영) DB 에서만 의미가 있으며, 신규 빈 DB 에서는 0건 처리 후 DataInitializer 가 다시 시드한다.

-- FK 순서: payments / reservations 가 reservation_groups 를 참조하므로 자식부터 삭제한다.
DELETE FROM payments;
DELETE FROM reservations;
DELETE FROM reservation_groups;

-- 예약이 모두 사라졌으므로 좌석을 다시 예매 가능 상태로 되돌린다.
UPDATE seats SET status = 'AVAILABLE' WHERE status <> 'AVAILABLE';
