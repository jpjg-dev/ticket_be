# Popular Event Payment E2E Spike Design

## Goal

대기열을 추가하지 않고 인기 공연 조회부터 좌석 선점, 결제 준비, Mock PG 승인, 결제 확정까지 이어지는 사용자 여정의 성능과 정합성을 검증한다.

## Runtime Boundary

- k6는 기존 백엔드 API만 호출한다.
- 백엔드는 실제 운영 코드인 `TossPaymentClient`를 그대로 사용한다.
- 외부 경량 Mock PG 서버는 `performance/mock-pg/`에서 별도 프로세스로 실행한다.
- `application-test.yaml`에서만 `toss.payments.base-url`을 `http://127.0.0.1:18080`으로 덮어쓴다.
- E2E Spike 실행 시 백엔드는 `dev,test` 프로필 조합으로 실행한다. `dev`의 로컬 TLS 설정을 유지하고 `test`의 Mock PG URL을 적용한다.
- 실제 Toss 주소를 가진 기본 `application.yaml`과 운영 코드는 변경하지 않는다.

## Mock PG Contract

Mock PG는 E2E 승인에 필요한 최소 API만 제공한다.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Mock PG 실행 확인 |
| `POST` | `/v1/payments/confirm` | 결제 승인 응답 반환 |

승인 요청의 `paymentKey`, `orderId`, `amount`를 검증하고 Toss 승인 응답과 동일한 핵심 필드를 반환한다.

```json
{
  "paymentKey": "perf-payment-key",
  "orderId": "order-id",
  "totalAmount": 2200,
  "currency": "KRW",
  "method": "CARD",
  "status": "DONE"
}
```

`Idempotency-Key`별 최초 응답을 메모리에 저장하고 같은 키의 재요청에는 동일 응답을 반환한다. 취소와 장애 주입은 이번 E2E Spike 범위에서 제외한다.

## k6 User Journey

각 iteration은 사용자 한 명의 완료 가능한 여정을 나타낸다.

1. 공연 목록을 조회한다.
2. 대상 인기 공연 상세를 조회한다.
3. 대상 회차의 좌석을 조회한다.
4. 설정된 인기 좌석 범위에서 `AVAILABLE` 좌석 하나를 선택한다.
5. 예약을 생성한다.
6. 예약 group 기준으로 결제를 준비한다.
7. iteration별 Mock `paymentKey`를 생성한다.
8. 결제를 승인한다.
9. 승인 응답에서 `APPROVED / CONFIRMED / BOOKED`를 확인한다.

좌석이 이미 선점됐거나 선택 가능한 인기 좌석이 없으면 정상 경합 거부로 기록한다. 인증 실패, `5xx`, 파싱 실패, 결제 단계 실패, 잘못된 최종 상태는 예상 밖 오류로 기록한다.

## Metrics

- 전체 여정 p95
- 공연 목록, 공연 상세, 좌석 조회, 예약 생성, 결제 준비, 결제 승인 단계별 p95
- 예약 성공 수와 정상 경합 거부 수
- 결제 완료 수와 결제 완료율
- 초당 결제 완료 건수
- 예상 밖 오류율

## Database Verification

실행 후 DB MCP로 아래 조건을 확인한다.

- 동일 좌석에 대한 중복 active reservation `0`
- reservation group의 부분 성공 `0`
- `Payment=APPROVED`, `ReservationGroup=CONFIRMED`, `Reservation=CONFIRMED`, `Seat=BOOKED` 상태 불일치 `0`

## Out Of Scope

- 대기열 기능
- 실제 Toss 결제창 인증
- 실제 Toss API 부하 테스트
- Mock PG 취소 API
- timeout, retry, circuit breaker 장애 주입

