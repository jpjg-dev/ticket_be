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

- 전달 보장은 `at-least-once`로 봅니다.
- Kafka 발행 실패 시 `PENDING`을 유지하고 이후 재시도합니다.
- 반복 실패는 `HOLD_MANUAL`로 격리하고 운영 알림과 수동 재처리 대상으로 둡니다.
- Kafka 발행 성공 후 `PUBLISHED` 반영 전에 장애가 나면 같은 `eventId`가 다시 발행될 수 있습니다.
- Consumer는 `eventId` 처리 이력을 기준으로 중복 상태 변경을 막아야 합니다.
- 일반 애플리케이션 로그는 Outbox 대상이 아닙니다. 결제 상태와 함께 보존해야 하는 도메인 이벤트만 Outbox에 저장합니다.

## paymentId별 순서와 장애 격리

Polling Relay는 전체 Outbox의 첫 실패에서 멈추지 않습니다. 대신 각 `paymentId`에서 가장 오래된 미발행 이벤트 한 건만 claim합니다.

```text
Payment A: Approved(PENDING/HOLD) -> Canceled(차단)
Payment B: Approved(PENDING)      -> 계속 발행 가능
```

- 선행 이벤트가 `PENDING`, backoff, lease 또는 `HOLD_MANUAL`이면 같은 결제의 후속 이벤트만 막습니다.
- 다른 `paymentId`는 같은 relay 주기에서 계속 처리합니다.
- claim은 PostgreSQL `FOR UPDATE SKIP LOCKED`와 `claimToken`/lease를 사용합니다.
- Kafka ACK나 실패 결과는 현재 `claimToken`이 일치할 때만 반영해 오래된 worker가 새 작업 결과를 덮지 못하게 합니다.
- claim 후 프로세스가 종료되면 lease 만료 뒤 같은 `eventId`를 다시 발행할 수 있으므로 전달 보장은 `at-least-once`입니다.

Kafka 호출은 DB 트랜잭션 밖에서 수행합니다.

```text
Tx1: paymentId별 선두 이벤트 claim
외부 호출: Kafka publish + broker ACK 대기
Tx2: PUBLISHED 또는 retry/HOLD_MANUAL 반영
```

## 재시도 분류

| 실패 종류 | 처리 |
| --- | --- |
| broker/network/timeout | capped exponential backoff로 계속 재시도 |
| 직렬화 불가/record too large | 해당 이벤트를 즉시 `HOLD_MANUAL`로 격리 |
| 분류되지 않은 오류 | 설정된 횟수까지 재시도한 뒤 `HOLD_MANUAL` |

초기 backoff는 `5s`, 최대 `5m`이며 운영 설정으로 조정합니다. 전역 Kafka 장애를 이유로 모든 이벤트를 `HOLD_MANUAL`로 바꾸지 않습니다. `lastError`에는 payload나 외부 응답을 저장하지 않고 오류 코드와 예외 종류만 남깁니다.

## 현재 구현 범위

- 결제 이벤트 계약과 Outbox 출력 포트
- PostgreSQL Outbox 테이블과 JPA 저장 어댑터
- 정상·보정 승인 및 정상·보정 취소의 최종 상태 전이와 Outbox 저장 연결
- 원자성·중복 생성 방지 테스트
- paymentId별 선두 이벤트 claim과 lease fencing
- DB Polling Relay와 Kafka Publisher 어댑터
- 재시도/backoff/`HOLD_MANUAL` 격리
- Outbox backlog, 활성 lease, 발행 결과·시간 메트릭

## Audit Consumer와 Inbox

첫 Consumer는 Booking 상태를 변경하지 않는 결제 감사 Consumer입니다. Consumer는 record 단위로 다음 로컬 트랜잭션을 실행합니다.

```text
Kafka record 수신
-> payload strict 검증 + canonical SHA-256
-> Inbox eventId 중복 확인
-> Inbox INSERT + 감사 이력 INSERT
-> DB COMMIT
-> listener 정상 반환 후 Kafka offset 반영
```

- DB 커밋 전 장애는 offset이 반영되지 않아 같은 record를 다시 처리합니다.
- DB 커밋 후 offset 반영 전 장애는 재수신되지만 Inbox `eventId`가 중복 업무 처리를 막습니다.
- 같은 `eventId`와 같은 canonical hash는 멱등 no-op입니다.
- 같은 `eventId`와 다른 hash는 payload 충돌로 판단해 원본 감사 이력을 변경하지 않고 DLT로 격리합니다.
- malformed, unknown field, `paymentKey`, key/paymentId 불일치와 미지원 version도 DLT 대상입니다.
- DB 연결·트랜잭션 장애는 DLT로 넘기지 않고 partition을 유지한 채 재시도합니다.

현재 이벤트에는 aggregate version이 없으므로 감사 기록만 수행합니다. Booking 같은 상태형 Consumer를 도입할 때는 v2 계약에 aggregate version을 추가해 gap과 순서 역전을 검출합니다.

## 운영 수명주기

- `HOLD_MANUAL` Outbox는 ADMIN API로만 `PENDING` 재진입할 수 있으며 관리자 ID·사유·시각을 별도 감사 테이블에 같은 트랜잭션으로 기록합니다.
- `PUBLISHED` Outbox는 14일이 지나고 audit Consumer가 같은 `eventId + canonical payloadHash`를 처리한 경우에만 bounded batch로 정리합니다.
- 과거 hash가 없거나 audit 증거가 없는 Outbox는 자동 삭제하지 않습니다.
- Inbox는 DLT 30일보다 긴 90일을 유지한 뒤 정리합니다.
- 감사 이력은 Inbox와 분리해 자동 삭제하지 않습니다.
- Inbox가 삭제된 뒤 장기 replay가 발생해도 감사 이력의 `eventId + payloadHash`로 중복과 충돌을 다시 판정합니다.
- cleanup은 relay와 다른 단일 스레드 scheduler를 사용합니다.

다음 단계는 상태형 Booking Consumer, DLT 수동 replay와 `PUBLISHED` 보존 기간의 운영 데이터 기반 조정입니다.
