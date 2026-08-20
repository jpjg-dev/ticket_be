# 결제 Transactional Outbox 설계

## 목적

결제 승인·취소가 확정된 뒤 Kafka 후처리를 추가할 때, 내부 상태는 커밋됐지만 이벤트가 유실되는 이중 쓰기 문제를 막습니다. Kafka는 결제 정합성의 최종 책임자가 아니라 확정 이후 감사·알림·통계 처리를 분리하는 전달 경계로 사용합니다.

## 이벤트 계약

초기 이벤트는 `PaymentApproved.v1`, `PaymentCanceled.v1` 두 종류로 제한합니다. `CONFIRMING`과 `CANCELING`은 회색지대이므로 최종 이벤트를 만들지 않습니다.

공통 payload:

```text
eventId
eventType
eventVersion
paymentId
orderId
reservationGroupId
userId
totalAmount
currency
occurredAt
source: NORMAL | RECOVERY
```

외부 PG의 `paymentKey`는 소비 계약에 필요하지 않고 노출 위험이 있으므로 제외합니다. Kafka partition key는 이후 Publisher를 구현할 때 `paymentId`로 고정해 같은 결제의 이벤트가 같은 파티션으로 전달되게 합니다.

`userId`는 감사와 사용자별 후처리를 위한 내부 식별자입니다. `source`는 정상·보정 경로를 구분하는 감사 메타데이터일 뿐이며 Consumer가 결제 상태 전이를 다르게 처리하는 분기 조건으로 사용하지 않습니다.

## 원자성 경계

다음 상태 전이와 Outbox INSERT를 같은 로컬 DB 트랜잭션으로 처리합니다.

```text
CONFIRMING -> APPROVED + PaymentApproved.v1
CANCELING  -> CANCELED + PaymentCanceled.v1
```

정상 처리와 보정 처리 모두 같은 원칙을 적용합니다. Outbox 직렬화나 INSERT가 실패하면 결제·예매·좌석 상태 전이도 함께 롤백합니다. PG가 이미 성공했다면 기존 `CONFIRMING` 또는 `CANCELING` 회색지대를 유지하고 보정 경로가 최종 상태와 Outbox를 함께 다시 확정합니다.

## 전달 정책

> 현재 구현은 Outbox 저장 경계까지입니다. Publisher가 연결되기 전에는 이벤트가 `PENDING`에 남으므로 이 중간 단계만 운영 배포하지 않습니다.

- 전달 보장은 `at-least-once`로 봅니다.
- Kafka 발행 실패 시 `PENDING`을 유지하고 이후 재시도합니다.
- 반복 실패는 `HOLD_MANUAL`로 격리하고 운영 알림과 수동 재처리 대상으로 둡니다.
- Kafka 발행 성공 후 `PUBLISHED` 반영 전에 장애가 나면 같은 `eventId`가 다시 발행될 수 있습니다.
- Consumer는 `eventId` 처리 이력을 기준으로 중복 상태 변경을 막아야 합니다.
- 일반 애플리케이션 로그는 Outbox 대상이 아닙니다. 결제 상태와 함께 보존해야 하는 도메인 이벤트만 Outbox에 저장합니다.

## 이번 구현 범위

- 결제 이벤트 계약과 Outbox 출력 포트
- PostgreSQL Outbox 테이블과 JPA 저장 어댑터
- 정상·보정 승인 및 정상·보정 취소의 최종 상태 전이와 Outbox 저장 연결
- 원자성·중복 생성 방지 테스트

Kafka Broker, Publisher, Consumer, 재시도 백오프 수치, 보존 기간과 정리 작업은 다음 단계에서 구현합니다.
