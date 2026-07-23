# 좌석 분산 락 전략

## 목적

인기 좌석에 요청이 몰릴 때 DB 비관적 락 대기자가 Hikari connection을 점유하던 구조를 Redis 선행 잠금으로 옮깁니다. Redis 락은 정상 경합을 줄이는 조정 장치이고, 좌석 정합성의 최종 책임은 PostgreSQL의 `Seat.version`이 가집니다.

## 정상 흐름

```text
Queue admission
-> seatIds distinct + 오름차순 정렬
-> Redis RLock 즉시 획득
-> 일반 Seat 조회
-> Seat AVAILABLE -> HELD 도메인 전이
-> ReservationGroup / Reservation 저장
-> @Version 조건으로 DB commit
-> Redis 락 역순 해제
```

`ReservationService`는 Spring 트랜잭션 프록시를 통과하므로 메서드 반환 전에 flush와 commit이 끝납니다. 외부 `SeatReservationCoordinator`의 `finally` 성격인 `SeatLockHandle.close()`는 그 반환 이후 실행됩니다.

## TTL 만료와 최종 방어

명시적인 짧은 lease time은 사용하지 않고 Redisson watchdog을 사용합니다. 초기 watchdog timeout은 `30s`이며 소유자가 살아 있는 동안 갱신됩니다.

GC 정지나 네트워크 단절로 락이 작업 중 만료되면 두 요청이 같은 `Seat(version=N)`을 읽을 수 있습니다. 먼저 커밋한 요청만 다음 조건을 만족합니다.

```sql
UPDATE seats
SET status = 'HELD', version = version + 1
WHERE id = ? AND version = ?;
```

나중 요청은 갱신 행이 `0`이 되어 낙관적 락 예외로 트랜잭션 전체가 롤백됩니다. 같은 좌석을 다시 시도해도 이미 `HELD`일 가능성이 높으므로 서버 자동 재시도는 하지 않고 `409 Conflict`로 즉시 실패시킵니다.

## 다중 좌석 정책

- 좌석 ID를 중복 제거하고 오름차순으로 잠급니다.
- 일부 락 획득에 실패하면 이미 얻은 락을 역순으로 해제합니다.
- 일부 락의 해제를 확인할 수 없으면 소유권이 불명확하므로 DB fallback을 실행하지 않고 `503`으로 fail-closed 합니다.
- 어느 좌석에서든 버전 충돌이 발생하면 같은 DB 트랜잭션의 예약 묶음 전체를 롤백합니다.
- 락 해제는 현재 스레드가 소유한 락만 수행해 만료 후 새 소유자의 락을 지우지 않습니다.

## Redis 장애 fallback

Redis 장애가 첫 좌석 락 획득 전 확인된 경우에만 기존 DB `PESSIMISTIC_WRITE` 경로를 사용합니다. fallback은 fair semaphore로 동시 실행을 제한하고, 허용량을 넘으면 `Retry-After`가 포함된 `503`을 반환합니다.

| 상황 | 처리 |
| --- | --- |
| 다른 요청이 Redis 좌석 락 보유 | 즉시 `409` |
| Redis 장애, 획득한 좌석 락 없음 | 제한된 DB 비관적 락 fallback |
| Redis 장애, 일부 좌석 락 획득 | fallback 금지, `503` |
| DB `@Version` 충돌 | 재시도 없이 `409`, 전체 롤백 |
| commit 후 unlock 실패 | 성공 응답 유지, watchdog 만료로 정리 |

## 초기 운영값

| 설정 | 초기값 | 근거 |
| --- | ---: | --- |
| 획득 wait time | `0ms` | 이미 다른 사용자가 처리 중인 같은 좌석에서 DB 진입과 순차 재시도를 막습니다. |
| watchdog timeout | `30s` | Redisson 기본 기준으로 시작하며 실제 트랜잭션 시간을 계측한 뒤 조정합니다. |
| fallback 동시 실행 | `2` | Redis 장애가 DB connection pool 전체를 소모하지 않게 제한합니다. |
| fallback 대기 | `100ms` | 보호 용량 초과 요청을 오래 붙잡지 않습니다. |

이 값은 성능·장애 테스트의 시작값입니다. 분산 락 적용 전후의 Hikari pending, DB lock wait, reservation p95, Redis 오류 및 CPU를 같은 조건으로 비교한 뒤 확정합니다.

## 선택 기준과 트레이드오프

| 선택 | 얻는 점 | 비용 |
| --- | --- | --- |
| Redis RLock | 같은 좌석의 DB connection 대기를 앞단으로 이동 | Redis 왕복과 장애 정책이 추가됩니다. |
| `@Version` | TTL 만료·네트워크 분할 시 DB가 최종 충돌을 검출 | 예외 상황에서 한 트랜잭션이 롤백됩니다. |
| 자동 재시도 없음 | 경합 증폭과 중복 예약 시도를 막음 | 사용자가 좌석 상태를 새로 조회해야 합니다. |
| 조건부 UPDATE 미채택 | 상태 전이 책임을 `Seat` 도메인에 유지 | 가장 짧은 단일 SQL 경로보다 처리량이 낮을 수 있습니다. |
| 제한 DB fallback | Redis 장애에도 소량 요청을 처리 | 순서를 이어받지 못하며 허용량 밖 요청은 실패합니다. |

조건부 UPDATE도 정합성을 보장할 수 있지만 `AVAILABLE -> HELD` 조건이 DB 쿼리와 도메인 양쪽에 중복됩니다. 현재는 정상 경합을 Redis가 줄이고, 드문 이탈만 `@Version`으로 검출하는 편이 상태 전이 책임과 맞다고 판단했습니다.

## 검증

- Redisson 실제 Redis 통합 테스트로 같은 좌석의 즉시 거절과 소유자 해제 후 재획득을 확인합니다.
- Redis 보호가 사라진 조건에서 PostgreSQL 동시 요청을 실행해 하나의 예약 묶음만 전체 성공하는지 확인합니다.
- 단위 테스트로 정렬 획득, 역순 해제, 낙관적 충돌 무재시도, 부분 획득 장애 fail-closed를 확인합니다.
