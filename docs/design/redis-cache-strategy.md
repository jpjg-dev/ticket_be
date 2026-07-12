# Redis Cache Strategy

## 목적

공연 목록/상세 조회 캐시는 기존 Caffeine 로컬 캐시에서 Redis 공유 캐시로 전환했습니다. 단일 인스턴스에서는 로컬 캐시가 더 단순하지만, 이후 서비스 분리와 다중 인스턴스 운영을 고려하면 캐시가 인스턴스 메모리에 묶이지 않아야 합니다.

이번 단계의 목적은 좌석처럼 상태 전이가 잦은 데이터를 캐시하는 것이 아니라, 변경 빈도가 낮고 반복 조회가 많은 공연 목록/상세를 Redis Cache-Aside로 옮겨 공유 캐시 동작과 관측 지표를 확인하는 것입니다.

## 적용 범위

| 대상 | 적용 여부 | 이유 |
| --- | --- | --- |
| 공연 목록 | 적용 | 변경 빈도가 낮고 반복 조회가 많습니다. |
| 공연 상세 | 적용 | 변경 빈도가 낮고 같은 공연 상세 조회가 반복됩니다. |
| 좌석 상태 | 제외 | 예약, 만료, 결제 취소가 좌석 상태를 바꾸므로 캐시 동기화 비용이 큽니다. |
| 예약 상태 | 제외 | 사용자 선점과 만료 상태 전이가 정합성에 직접 연결됩니다. |
| 결제 상태 | 제외 | 외부 PG 연동과 보정 스케줄러가 상태를 변경합니다. |

## 캐시 전략

전략은 `Cache-Aside`입니다.

```text
요청
→ Redis 조회
→ miss면 DB 조회
→ Redis 저장
→ 응답
```

Spring `@Cacheable`을 사용하고, 운영/성능 프로필에서는 `RedisCacheManager`가 캐시 저장소를 담당합니다.

목록 캐시는 `List<EventListResponse>`를 그대로 Redis에 저장하지 않고 `EventListCacheResponse` wrapper DTO로 감쌉니다. Redis 직렬화 경계에서는 제네릭 목록의 원소 타입이 흐려질 수 있어, cache value의 최상위 타입을 명확히 하기 위해서입니다. 상세 캐시는 단일 DTO인 `EventDetailResponse`를 그대로 저장합니다.

## Caffeine에서 Redis로 전환한 이유

| 기준 | Caffeine 로컬 캐시 | Redis 공유 캐시 |
| --- | --- | --- |
| 단일 인스턴스 성능 | JVM 메모리 접근이라 가장 단순하고 빠릅니다. | 네트워크 왕복이 있어 단일 인스턴스에서는 더 느릴 수 있습니다. |
| 다중 인스턴스 | 인스턴스마다 캐시가 따로 생깁니다. | 여러 인스턴스가 같은 캐시를 공유할 수 있습니다. |
| 재시작 | 애플리케이션 재시작 때 캐시가 사라집니다. | Redis가 살아 있으면 애플리케이션 재시작과 분리됩니다. |
| 관측 | 애플리케이션 내부 캐시라 공유 상태를 보기 어렵습니다. | Prometheus/Grafana로 hit/miss를 관측하기 쉽습니다. |

따라서 현재 단일 VM에서도 Redis를 도입하되, 좌석/예약/결제 상태처럼 정합성에 민감한 데이터는 캐시하지 않습니다.

## Key 네이밍

Redis key는 사람이 읽을 수 있도록 콜론(`:`) 기반 namespace를 사용합니다.

```text
ticketledger:cache:event:list::all
ticketledger:cache:event:detail::1
```

여기서 `:`는 Redis에서 흔히 쓰는 namespace 구분자입니다. `ticketledger:cache:event:list`처럼 서비스, 용도, 도메인, 대상을 계층적으로 읽게 합니다.

`::`는 Spring Data Redis Cache의 기본 구분자입니다. Spring은 cache name 뒤에 `::`를 붙이고, 그 뒤에 실제 cache key를 붙입니다.

```text
{cachePrefix}{cacheName}::{cacheKey}
```

따라서 `event:list` cache에 `all` key를 저장하면 최종 key는 다음처럼 됩니다.

```text
ticketledger:cache:event:list::all
```

목록 조회는 파라미터가 없기 때문에 key를 명시하지 않으면 Spring 기본 key인 `SimpleKey.EMPTY`가 사용됩니다. Redis에서 사람이 보기에는 `SimpleKey []` 같은 형태로 보일 수 있어, 목록 조회는 `key = "'all'"`로 명시합니다.

## TTL

TTL은 기존 YAML 값을 그대로 사용합니다.

| cache name | 설정 |
| --- | --- |
| `event:list` | `cache.event.list.ttl` |
| `event:detail` | `cache.event.detail.ttl` |

현재 1차 적용은 TTL 기반 만료만 사용합니다. Redis 장애 시 fallback 정책, cache stampede 방어, 상태성 Redis 사용 정책은 후속 단계에서 분리해 다룹니다.

## 검증 결과

Redis 적용 후 다음 항목을 확인했습니다.

| 항목 | 확인 내용 |
| --- | --- |
| Redis key | `ticketledger:cache:event:list::all`, `ticketledger:cache:event:detail::{eventId}` 형태로 저장됨 |
| TTL | `event:list=60s`, `event:detail=5m` |
| 값 구조 | 목록 캐시는 `{"events":[...]}` wrapper DTO 형태로 저장됨 |
| Grafana | `cache_gets_total` 기반 hit ratio, hit/miss rate 패널 추가 |
| k6 조회 테스트 | `20 VU / 30s`, 요청 `137,170`, 실패율 `0%`, 목록 p95 `6.76ms`, 상세 p95 `6.48ms` |

## CI 통합

테스트 프로필도 Redis 캐시를 사용하므로 GitHub Actions build 단계에서 Redis service container를 함께 띄웁니다. workflow job은 runner에서 직접 실행되기 때문에 Redis는 `localhost:6379`로 매핑하고, Gradle build 단계에 `REDIS_HOST=localhost`, `REDIS_PORT=6379`를 주입합니다.

## 후속 과제

- Redis 장애 시 제한적 DB fallback 정책
- cache stampede 방어
- refresh token, 멱등성 key, 대기열, 분산락 적용 여부 별도 검토
