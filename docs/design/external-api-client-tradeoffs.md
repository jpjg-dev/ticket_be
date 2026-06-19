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
| PG 호출 timeout 명시 | 외부 응답 지연이 서버 thread를 무한 점유하지 않습니다. | timeout은 실패 확정이 아니므로 `CONFIRMING` 보정 흐름과 함께 해석해야 합니다. |
| PG 호출을 DB 트랜잭션 밖에서 수행 | 외부 호출 시간만큼 DB 락을 오래 잡지 않습니다. | 중간 상태 `CONFIRMING`과 보정 스케줄러가 필요합니다. |
| WebFlux 미도입 | 구현 범위와 학습 비용을 줄였습니다. | 다량 외부 API 병렬 호출에는 적합하지 않습니다. |
| OpenFeign 보류 | 단일 PG 연동에 필요한 의존성을 줄였습니다. | 외부 API가 늘어나면 인터페이스 기반 정리가 필요할 수 있습니다. |
| Retry/Circuit Breaker 보류 | 현재 실패 모드에는 `CONFIRMING` 마커, PG 조회, 보정 스케줄러가 직접 대응합니다. | 외부 장애율·알림 체계·연동 대상 증가 시 별도 도입 검토가 필요합니다. |

## 참고 기준

- Spring Framework REST Clients: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Toss Payments 결제 흐름: https://docs.tosspayments.com/guides/v2/get-started/payment-flow
- Toss Payments 멱등성 설명: https://docs.tosspayments.com/blog/what-is-idempotency
