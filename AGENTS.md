## Rules
- 결제는 반드시 트랜잭션으로 처리
- 중복 결제 방지 로직 필수
- 상태값: PENDING, SUCCESS, FAIL

## Architecture
- Controller → Service → Repository 구조
- Service에서만 비즈니스 로직 처리

## Constraints
- 동시성 문제 고려
- idempotency 적용 필수