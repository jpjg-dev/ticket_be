# Kafka Outbox 회귀·장애 테스트

## 목적

결제 Outbox와 Audit Consumer 도입이 정합성을 유지하는지 확인하고, 제한된 운영 자원에서 동기 요청 경로에 미치는 비용을 분리해 측정합니다. Kafka는 처리 속도를 높이기 위한 기능이 아니라, 결제 확정 이후 이벤트를 유실 없이 전달하고 독립적으로 재처리하기 위한 경계입니다.

## 환경과 비교 기준

- 측정일: 2026-08-28
- 부하 스크립트: `performance/k6/popular-event-payment-arrival-rate-spike.js`
- 요청 부하: 50초 동안 최대 `1,000 iterations/s`
- 서비스 자원: backend, PostgreSQL, Redis, Kafka, Prometheus, Grafana가 CPU `0,1`을 공유합니다.
- 외부 부하 자원: k6와 Mock PG는 서비스 CPU 집합 밖에서 실행합니다.
- 비교 기준: 2026-07-22의 `post-lock-measured-1~3.json`을 가장 가까운 pre-Outbox 기준선으로 사용합니다.

과거 기준선과 현재 측정은 동일한 부하 스크립트·좌석 1,000개·2 vCPU 제약을 사용하지만 같은 commit의 A/B 측정은 아닙니다. 따라서 차이는 방향성 판단에 사용하고, Outbox 하나의 순수 비용으로 단정하지 않습니다.

## 결과

### Pre-Outbox 기준선

| 실행 | 완료 결제 | 전체 여정 p95 | 좌석 조회 p95 | 예약 p95 | 결제 준비 p95 | 결제 승인 p95 | dropped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,000 | 2.79s | 0.91s | 1.28s | 0.94s | 0.90s | 512 |
| 2 | 1,000 | 2.93s | 1.07s | 2.03s | 1.57s | 1.61s | 217 |
| 3 | 1,000 | 1.89s | 0.58s | 0.88s | 0.68s | 0.70s | 0 |
| 평균 | 1,000 | 2.54s | 0.85s | 1.40s | 1.06s | 1.07s | 243 |

### Outbox + Kafka Relay + Audit Consumer

| 실행 | 완료 결제 | 전체 여정 p95 | 좌석 조회 p95 | 예약 p95 | 결제 준비 p95 | 결제 승인 p95 | dropped | unexpected |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,000 | 10.24s | 3.38s | 4.39s | 4.12s | 4.08s | 3,703 | 0 |
| 2 | 1,000 | 7.24s | 2.69s | 1.85s | 1.49s | 1.57s | 3,184 | 0 |
| 3 | 1,000 | 4.97s | 1.85s | 2.58s | 2.01s | 2.05s | 1,097 | 0 |
| 평균 | 1,000 | 7.48s | 2.64s | 2.94s | 2.54s | 2.57s | 2,661 | 0 |

모든 실행은 결제 완료 `1,000`, 중복 좌석·부분 성공·예상 밖 오류 `0`을 유지했습니다. 실행 종료 직후 `PENDING` Outbox는 각각 `462`, `383`, `369`건이었고, 모두 30초 안에 다음 상태로 수렴했습니다.

```text
Outbox PUBLISHED = 1,000
Inbox = 1,000
Audit = 1,000
```

## 비용 분리

Kafka broker, Relay와 Audit Consumer를 끄고 동기 트랜잭션의 Outbox INSERT만 유지한 warm 진단 결과는 다음과 같습니다.

| 완료 결제 | 전체 여정 p95 | 좌석 조회 p95 | 예약 p95 | 결제 준비 p95 | 결제 승인 p95 | dropped |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 3.97s | 1.33s | 2.42s | 2.07s | 2.14s | 453 |

이 진단은 한 번만 실행했으므로 최종 기준선이 아니라 원인 분리용입니다. 동기 Outbox INSERT에도 비용은 있지만, 전체 Kafka 경로보다 회귀 폭이 작았습니다.

Prometheus에서 전체 Kafka 경로 실행 중 다음 최대값을 확인했습니다.

| 지표 | 최대값 |
| --- | ---: |
| 시스템 CPU | 100% |
| backend process CPU | 84.6% |
| Hikari active | 10 |
| Hikari pending | 188 |
| Outbox PENDING backlog | 803 |
| 가장 오래된 PENDING | 57s |

현재 회귀는 단일 쿼리나 직렬화 하나보다, 2 vCPU 안에서 사용자 요청·Outbox Relay·Audit Consumer·Kafka broker가 CPU와 DB connection을 함께 경쟁한 영향으로 해석하는 것이 타당합니다.

## 장애 복구 검증

- Kafka broker를 실제 pause한 상태에서 발행하면 Outbox가 `PENDING`을 유지하고 retry가 예약됩니다.
- broker를 unpause한 뒤 재실행하면 동일 이벤트가 `PUBLISHED`로 전환됩니다.
- 같은 `eventId` 재수신은 Inbox에서 중복 제거합니다.
- malformed record는 DLT로 격리하고 같은 partition의 후속 정상 record는 계속 처리합니다.
- DB 장애는 offset을 진행하지 않고 같은 record를 재전달합니다.

## 판단

- 정합성·복구 목적은 달성했습니다.
- 현재 단일 2 vCPU 배치에서 Kafka 후처리를 무제한으로 요청 경로와 경쟁시키는 구성은 성능 회귀가 큽니다.
- DB pool을 먼저 늘리면 CPU 포화 상태에서 경합만 키울 수 있으므로 우선순위가 아닙니다.
- 다음 조정은 Relay batch/주기와 Consumer 처리량을 CPU·Hikari pending·backlog 기준으로 제한하거나, Kafka broker/후처리 worker의 자원을 요청 경로와 분리하는 방향으로 진행합니다.
- 대기열은 사용자 요청 유입을 제어하고, Relay 제한은 이미 커밋된 backlog의 배출 속도를 제어하므로 두 보호 장치를 별도로 관측합니다.
