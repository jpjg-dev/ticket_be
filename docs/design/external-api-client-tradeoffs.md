# 외부 API 클라이언트 선택 기준

## 문서 목적

이 문서는 TicketLedger 백엔드가 토스페이먼츠 결제 승인/취소 API를 호출할 때 왜 `RestClient`를 선택했는지 정리합니다.

결제 연동은 단순한 HTTP 호출이 아니라 내부 예약 상태와 외부 PG 상태를 연결하는 흐름입니다. 그래서 클라이언트 선택 기준도 사용 편의성보다 **상태 정합성, 설명 가능성, 현재 프로젝트 범위**를 우선했습니다.

## 요구사항

토스페이먼츠 승인 흐름에서 백엔드는 다음 순서로 동작합니다.

```text
paymentKey, orderId, amount 수신
-> 내부 DB 기준 예약 상태 검증
-> Payment READY -> CONFIRMING 커밋
-> 만료 여부 검증
-> 결제 금액 검증
-> 토스페이먼츠 승인 API 호출
-> PG 응답 검증
-> Payment / ReservationGroup / Reservation / Seat 상태 확정
```

외부 API 응답을 받은 뒤 내부 상태를 확정해야 하므로, 현재 구조에서는 동기 HTTP 호출이 가장 자연스럽습니다. 다만 PG 호출 자체는 DB 트랜잭션 밖에서 수행해 외부 응답 시간 동안 DB 락을 오래 잡지 않습니다.

## 후보 비교

| 후보 | 장점 | 비용 | 판단 |
| --- | --- | --- | --- |
| `RestTemplate` | 오래 사용되어 예제와 자료가 많습니다. | Spring Framework 6.x 기준 새 코드의 우선 선택지로 보기 어렵고 코드가 장황해질 수 있습니다. | 사용할 수는 있지만 새 프로젝트 기준으로는 제외했습니다. |
| `RestClient` | Spring Framework 6.1부터 제공되는 동기 클라이언트이고 fluent API로 요청을 구성할 수 있습니다. | `RestTemplate`보다 레거시 예제가 적고 복잡한 선언형 기능은 직접 구성해야 합니다. | 현재 프로젝트에 선택했습니다. |
| `WebClient` | 비동기/논블로킹 호출과 다수 외부 API 호출에 유리합니다. | WebFlux 모델이 추가되고 현재 결제 흐름에는 과한 선택입니다. | 이번 단계에서는 제외했습니다. |
| `OpenFeign` | 인터페이스 기반 선언형 API 계약을 만들기 좋습니다. | Spring Cloud 의존성과 설정이 늘어납니다. 단일 PG 연동에는 구조가 큽니다. | 후속 외부 연동이 많아질 때 검토합니다. |

## 최종 선택

TicketLedger는 토스페이먼츠 연동용 HTTP 클라이언트로 `RestClient`를 사용합니다.

선택 이유:

- 현재 프로젝트는 Spring Boot 3.x 기반입니다.
- 결제 승인 후 내부 상태 전이가 바로 이어지는 동기 흐름입니다.
- WebFlux 기반 비동기 모델을 도입할 만큼 외부 호출이 많지 않습니다.
- 단일 PG 연동을 위해 OpenFeign과 Spring Cloud 의존성을 추가할 필요가 작습니다.
- `RestTemplate`보다 현재 Spring 기준에 맞는 선택입니다.

## 결제 흐름에서의 위치

```text
PaymentService.confirmPayment()
-> PaymentConfirmService.confirm()
-> Tx1: 내부 상태/금액/만료 검증 + READY -> CONFIRMING
-> RestClient로 Toss Payments 승인 API 호출
-> Toss 응답 검증
-> Tx2: Payment APPROVED
       ReservationGroup CONFIRMED
       Reservation CONFIRMED
       Seat BOOKED
```

중요한 기준은 외부 PG 승인 요청보다 내부 검증이 먼저라는 점입니다.

```text
외부 PG 승인 요청 전
우리 DB 기준 예약 상태와 금액을 먼저 검증합니다.
```

## 트레이드오프

| 선택 | 얻은 점 | 감수한 점 |
| --- | --- | --- |
| 동기 `RestClient` | 결제 승인 후 상태 전이 순서를 설명하기 쉽습니다. | 외부 API 응답 시간 동안 요청 thread가 대기합니다. |
| PG 호출 timeout 명시 | 외부 응답 지연이 서버 thread를 무한 점유하지 않습니다. | timeout은 실패 확정이 아니므로 `CONFIRMING`/`CANCELING` 보정 흐름과 함께 해석해야 합니다. |
| PG 호출을 DB 트랜잭션 밖에서 수행 | 외부 호출 시간만큼 DB 락을 오래 잡지 않습니다. 승인·취소·보정 전 경로에 예외 없이 적용합니다. | 중간 상태 `CONFIRMING`/`CANCELING`과 보정 스케줄러가 필요합니다. |
| WebFlux 미도입 | 구현 범위와 학습 비용을 줄였습니다. | 다량 외부 API 병렬 호출에는 적합하지 않습니다. |
| OpenFeign 보류 | 단일 PG 연동에 필요한 의존성을 줄였습니다. | 외부 API가 늘어나면 인터페이스 기반 정리가 필요할 수 있습니다. |
| 연산별 Circuit Breaker 적용 | 승인·조회·취소 장애가 서로의 회로를 열지 않고, 장애 중 반복 외부 호출을 빠르게 차단합니다. | 임계값과 OPEN 시간을 운영 지표로 조정해야 하며, OPEN 중 요청은 `503`으로 거절됩니다. |
| 자동 Retry 제외 | 승인·취소 중복 호출과 사용자 응답 지연을 늘리지 않습니다. 결과 불명은 기존 중간 상태와 보정 흐름이 담당합니다. | 일시 오류를 같은 요청에서 즉시 회복할 가능성은 포기합니다. |

## Circuit Breaker 경계

승인은 회로 permit을 먼저 확보한 요청만 `READY -> CONFIRMING` 트랜잭션에 진입합니다. 단순 상태 조회 후 트랜잭션에 들어가면 그 사이 회로가 열리는 경쟁 조건이 생길 수 있어, 실제 호출 권한 확보와 PG 호출 결과 기록을 하나의 permit으로 묶었습니다.

```text
confirm permit 확보
-> Tx1: READY -> CONFIRMING
-> PG 승인 호출
-> permit에 성공/실패 기록
-> Tx2: 내부 상태 확정
```

PG 회로는 `confirm`, `lookup`, `cancel`로 분리합니다. timeout·5xx·408·429는 외부 장애로 집계하고, 금액 오류나 잘못된 요청처럼 PG가 명확히 거절한 일반 4xx는 회로 실패율에서 제외합니다. 회로가 OPEN이면 `503 Service Unavailable`과 `Retry-After`를 반환합니다.

PG 조회가 성공해도 상태가 `READY`·`IN_PROGRESS`·`WAITING_FOR_DEPOSIT`이면 실패로 확정하지 않고 `CONFIRMING`을 유지합니다. `ABORTED`·`EXPIRED`·`CANCELED`처럼 최종 상태일 때만 내부 실패와 좌석 반환을 적용하며, 알 수 없는 신규 상태는 자동 변경하지 않고 수동 확인 대상으로 남깁니다.

보정 스케줄러는 PG 상태 조회가 선행 조건이므로 lookup 회로가 OPEN이면 신규 배치를 시작하지 않습니다. 처리 중 lookup 회로가 열려도 남은 건을 중단하고 다음 주기로 넘깁니다. 반면 cancel 회로만 OPEN인 경우에는 조회 결과가 이미 취소 완료일 수 있으므로 조회·내부 확정 기회까지 막지 않습니다.

## 외부 호출 실패 로그 표준화

Toss 승인/취소/조회 실패 로그가 서비스·보정 계층에 흩어져 필드가 제각각이라, 장애 시 원인 비교가 어려웠습니다. 그래서 외부 호출 실패 로그를 **호출 단위(`TossPaymentClient`)로 단일화**했습니다.

로그 위치 결정:

| 후보 | 판단 |
| --- | --- |
| 서비스 계층 공통 helper | `paymentId` 같은 내부 식별자까지 담을 수 있지만, 호출부 다섯 곳이 모두 helper를 거쳐야 해 번잡합니다. |
| `TossPaymentClient` 단일화 | 모든 Toss 호출이 지나는 유일 지점이라 형식을 한곳에서 강제할 수 있어 선택했습니다. 내부 식별자는 서비스의 비즈니스 결정 로그가 담당합니다. |

표준 필드(실패 시):

```text
event=TOSS_CALL_FAIL operation=CONFIRM orderId=... paymentKeyMasked=abc123***
idempotencyKey=confirm:... outcome=... httpStatus=... exceptionClass=... message=...
```

- `operation`: `CONFIRM`, `CANCEL`, `LOOKUP_BY_PAYMENT_KEY`, `LOOKUP_BY_ORDER_ID` (`TossOperation`).
- `outcome`: `TIMEOUT`(결과 불명 → `CONFIRMING` 보정 대상), `HTTP_ERROR`(상태코드 있음), `OTHER`(미분류).
- 호출별로 보유하지 않는 값(`cancel`의 `orderId` 등)은 `N/A`로 남깁니다.

원칙:

- `TossPaymentClient`가 실패를 로깅하고 Spring HTTP 예외를 `PaymentGatewayException`으로 변환합니다. 서비스 계층은 HTTP 구현 예외를 알지 않고 **비즈니스 결정**(조회 fallback, 보정 위임, 다음 주기 재시도)만 남겨 중복 로그를 없앱니다.
- `raw secret`, `Authorization` 헤더는 로그에 남기지 않습니다. `paymentKey`는 앞 6자만 노출하고 나머지는 마스킹합니다(`PaymentLogFormatter`, infrastructure 배치로 계층 역참조 방지).
- `TIMEOUT`은 실패 확정이 아니라 결과 불명이므로 `CONFIRMING` 보정 흐름과 함께 해석합니다.

## 참고 기준

- Spring Framework REST Clients: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Resilience4j CircuitBreaker: https://resilience4j.readme.io/docs/circuitbreaker
- Resilience4j Spring Boot 3: https://resilience4j.readme.io/docs/getting-started-3
- Toss Payments 결제 흐름: https://docs.tosspayments.com/guides/v2/get-started/payment-flow
- Toss Payments 멱등성 설명: https://docs.tosspayments.com/blog/what-is-idempotency
