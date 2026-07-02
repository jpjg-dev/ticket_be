# 좌석 선점 동시성 검증

## 문서 목적

이 문서는 인기 공연 오픈 시점에 여러 사용자가 같은 좌석 또는 겹치는 좌석 묶음을 동시에 예매할 때, TicketLedger가 중복 선점과 부분 성공을 어떻게 막는지 정리합니다.

핵심은 단순히 실패율을 낮추는 것이 아니라 **성공한 요청이 하나라면 그 요청의 좌석 전체만 선점되고, 실패 요청은 아무 reservation도 남기지 않는 것**입니다.

## 검증 범위

| 포함 | 제외 |
| --- | --- |
| `ReservationService.createReservation()` | 결제 승인 |
| 겹치는 `seatIds` 동시 요청 | 중복 결제 |
| 좌석 ID 정렬 | 결제 실패 |
| `PESSIMISTIC_WRITE` 좌석 조회 | 환불/취소 정책 |
| `ReservationGroup` 단위 원자성 | 외부 PG 연동 |

## 테스트 시나리오

여러 사용자가 동시에 다음과 같은 좌석 묶음을 요청합니다.

```text
요청 A: [A-1, A-2]
요청 B: [A-2, A-3]
요청 C: [A-1, A-2]
```

모든 요청이 공통 좌석을 일부 포함하므로 동시에 모두 성공하면 안 됩니다.

## 기대 결과

| 항목 | 기대 결과 |
| --- | --- |
| 성공 요청 | 하나의 `ReservationGroup`만 성공합니다. |
| 실패 요청 | 예매 row와 좌석 상태를 남기지 않습니다. |
| 좌석 상태 | 성공 group의 좌석만 `HELD`가 됩니다. |
| 중복 선점 | 같은 좌석에 활성 reservation이 2건 이상 생기지 않습니다. |
| 부분 성공 | group 안에서 일부 좌석만 저장되지 않습니다. |

## 현재 검증 결과

2026-05-28 기준 `ReservationConcurrencyTest`로 group 기준 동시성 테스트를 실행했습니다.

| 항목 | 결과 |
| --- | ---: |
| 동시 사용자 | `10` |
| 성공 요청 | `1` |
| 실패 요청 | `9` |
| 저장된 reservation | 성공 group의 좌석 `2`건 |
| 저장된 group | `1` |
| 최종 `HELD` 좌석 | `2` |

검증 결론:

- 겹치는 좌석 묶음 동시 요청에서 하나의 `ReservationGroup`만 전체 성공합니다.
- 실패 요청은 부분 reservation을 남기지 않습니다.
- `Seat` 비관적 락과 ID 정렬 정책이 group 단위 원자성 보장에 유효하게 동작했습니다.

## 락 순서 정책

다중 좌석 예약에서는 서로 다른 요청이 같은 좌석들을 다른 순서로 잡으려 하면 데드락 가능성이 커집니다.

예시:

```text
A 요청: [1, 2]
B 요청: [2, 1]
```

이를 줄이기 위해 같은 자원 집합은 항상 같은 순서로 잠금을 획득합니다.

| 계층 | 정책 |
| --- | --- |
| 애플리케이션 | `seatIds.distinct().sorted()` 적용 |
| DB 조회 | `order by s.id asc` 기준으로 `PESSIMISTIC_WRITE` 조회 |

## 결제·만료 경로 락 순서 점검

좌석 선점 외에도 결제 승인, 결제 취소, 예약 만료, 결제 보정은 같은 `Payment`, `ReservationGroup`, `Reservation`, `Seat` 상태를 함께 변경합니다.
따라서 데드락을 막기 위해 경로별 명시적 비관 락 획득 순서를 점검했습니다.

| 경로 | 명시적 락 획득 순서 | 비고 |
| --- | --- | --- |
| 좌석 선점 | `Seat(id asc)` | 좌석 ID 정렬 후 `PESSIMISTIC_WRITE` 조회 |
| 예약 만료 | `Payment -> ReservationGroup` | 이후 `Reservation`, `Seat` 상태 변경 |
| 결제 승인 Tx1 | `Payment` | `READY -> CONFIRMING` 마커 저장 |
| 결제 승인 Tx2 | `Payment` | 이후 `ReservationGroup`, `Reservation`, `Seat` 확정 |
| 결제 취소 | `Payment` | 이후 `ReservationGroup`, `Reservation`, `Seat` 취소/해제 |
| 결제 보정 | `Payment` | 이후 승인 확정 또는 실패/좌석 해제 |

현재 코드에서 `ReservationGroup -> Payment`처럼 위 순서를 거꾸로 잡는 명시적 비관 락 경로는 확인되지 않았습니다.
좌석 묶음은 항상 `Seat(id asc)` 순서로 잠그므로 `Seat 1 -> Seat 2`와 `Seat 2 -> Seat 1`이 서로 기다리는 형태의 좌석 간 데드락도 방어합니다.
또한 예약 만료는 `PENDING` group만 대상으로 하고, 결제 취소는 `APPROVED` payment만 대상으로 하므로 정상 상태 전이에서는 같은 group을 동시에 만료·취소 처리하지 않습니다.

다만 `Reservation`, `Seat` 변경은 명시적 `for update`가 아니라 엔티티 변경 후 flush 시점의 writer lock으로 반영됩니다.
따라서 이후 상태 전이 경로를 추가할 때는 다음 순서를 유지합니다.

```text
Payment -> ReservationGroup -> Reservation -> Seat(id asc)
```

현재 남은 리스크는 데드락보다 결제 취소·보정 환불에서 `Payment` 락을 잡은 상태로 외부 PG 취소 API를 호출해 락 대기 시간이 길어질 수 있다는 점입니다.
이 항목은 데드락 이슈가 아니라 트랜잭션 경계 최적화 후보로 별도 관리합니다.

## 사용한 테스트 도구

- JUnit
- `ExecutorService`
- `CountDownLatch`
- `AtomicInteger`

## 관련 문서

- [상태 전이 설계](../design/state-design.md)
- [테스트 체크리스트](TestCase.md)
- [성능 테스트 전략](../performance/performance-test-strategy.md)
