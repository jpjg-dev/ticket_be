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

애플리케이션의 `EventCache` 포트를 통해 Cache-Aside 흐름을 명시적으로 제어하고, Redis 구현은 `EventRedisCacheAdapter`가 담당합니다. `@Cacheable`의 기본 오류 처리만으로는 Redis 장애 시 모든 요청이 DB로 통과할 수 있어 사용하지 않습니다.

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

기본 TTL에는 `+-10%` jitter를 적용하고, 가장 가까운 회차 시작 시각을 넘지 않도록 실제 만료 시각을 제한합니다.

## 캐시 Redis 장애 정책

> **상태: 적용 완료.** 장애 격리, Circuit Breaker, key별 단일 로더, 제한된 DB fallback, TTL jitter와 회차 경계 TTL을 반영했습니다.

### 목표

캐시 Redis 장애가 백엔드와 PostgreSQL 장애로 전파되지 않게 합니다.

Redis가 중단되어도 일부 공연 조회는 DB fallback으로 처리하되, 모든 요청을 DB로 통과시키지 않습니다. 개별 조회 요청의 성공률을 일부 양보하더라도 예약·결제와 DB 전체를 보호하는 것을 우선합니다.

### 인스턴스 분리

캐시 Redis와 대기열·입장 토큰·멱등성 key·분산락을 저장할 상태 Redis를 별도 인스턴스로 분리합니다. Redis 논리 DB 번호는 메모리, eviction, 영속성 정책을 공유하므로 장애 격리 수단으로 사용하지 않습니다.

| 구분 | 캐시 Redis | 상태 Redis |
| --- | --- | --- |
| 용도 | 공연 목록·상세처럼 재생성 가능한 데이터 | 대기열, 입장 토큰, 멱등성 key, 분산락 |
| 메모리 초과 | `allkeys-lru` | `noeviction` |
| 영속성 | AOF·RDB 비활성화 | 별도 상태 Redis 정책에서 결정 |
| 장애 처리 | 제한된 DB fallback | 신규 처리 차단을 기본으로 별도 결정 |

캐시 Redis는 `ticket-redis-cache`로 분리하고 초기 `maxmemory`는 `256MB`로 둡니다. 운영에서는 host port를 노출하지 않고 Docker 내부 네트워크에서만 접근합니다.

### 제한된 DB fallback

Redis 연결 실패를 단순히 무시하면 같은 순간의 모든 요청이 DB로 전달될 수 있습니다. 따라서 fallback은 다음 두 단계로 제한합니다.

| 제한 | 초기값 | 목적 |
| --- | ---: | --- |
| 동일 cache key의 DB 로더 | `1` | 같은 목록·상세의 중복 DB 조회 방지 |
| 인스턴스당 전체 fallback | `2` | Hikari pool `10` 중 대부분을 예약·결제 경로에 보존 |
| 캐시 재생성 대기 | 최대 `300ms` | 짧은 재생성은 기다리되 요청 스레드 장기 점유 방지 |
| 재생성 락 TTL | `2s` | 로더 장애 시 영구 대기 방지 |
| 제한 초과 | `503` + `Retry-After: 1` | DB로 무제한 전달하지 않고 재시도 유도 |

Redis가 정상이고 cache miss가 발생하면 동일 key의 요청 하나만 DB를 조회하고 나머지는 캐시 재생성을 기다립니다. Redis 자체가 중단되어 분산 제어를 사용할 수 없으면 애플리케이션 로컬 세마포어로 인스턴스당 최대 `2`개의 DB fallback만 허용합니다.

백엔드 인스턴스가 여러 개라면 Redis 장애 시 전체 fallback 상한은 `인스턴스 수 x 인스턴스당 허용량`이 됩니다. 따라서 인스턴스 확장 시 이 값을 다시 계산합니다.

초기값 `2`, `300ms`, `2s`는 최적값이 아니라 서버 보호를 위한 시작점입니다. 장애 주입 테스트에서 DB CPU, Hikari pending connection, fallback 성공률과 `503` 비율을 측정한 뒤 조정합니다.

### Redis Circuit Breaker

Redis get/put/refresh lock 호출은 하나의 `eventRedisCache` 회로로 보호합니다. 최근 `10`회 중 최소 `5`회가 수집된 뒤 실패율이 `50%` 이상이면 `10초` 동안 OPEN하고, HALF_OPEN에서는 `1`회만 복구 여부를 확인합니다.

Redis 연결 수립과 명령 응답 대기는 성격이 다르므로 `spring.data.redis.connect-timeout`과 `spring.data.redis.timeout`을 분리합니다. 연결 timeout은 네트워크 단절 시 회로에 실패를 빠르게 전달하고, read timeout은 연결된 Redis 명령 응답의 최대 대기 시간을 제한합니다.

OPEN 중 Redis 호출은 즉시 `EventCacheAccessException`으로 변환되어 기존 제한 DB fallback 정책으로 전달됩니다. Circuit Breaker는 DB fallback 상한을 대신하지 않습니다. 회로는 반복 timeout을 줄이고, 세마포어와 key별 단일 로더는 Redis가 없을 때 DB로 전달되는 요청 수를 제한합니다.

### TTL 만료 동시성 제어

여러 cache key가 같은 시점에 만료되는 현상을 줄이기 위해 TTL에 `+-10%` jitter를 적용합니다.

```text
공연 목록 60초 -> 54~66초
공연 상세 5분 -> 270~330초
```

동일 key가 만료된 경우 jitter만으로 중복 조회를 막을 수 없으므로, 해당 key의 DB 로더는 하나만 허용합니다. 로더가 실패하거나 중단되면 락 TTL이 지난 뒤 다음 요청이 다시 시도합니다. 실패 응답과 `null`은 캐시하지 않습니다.

공연 응답은 현재 시각을 기준으로 시작하지 않은 회차만 포함하므로, 캐시 TTL이 가장 가까운 회차 시작 시각을 넘어가면 시작된 회차가 남을 수 있습니다. 실제 TTL은 다음 두 값 중 작은 값으로 계산합니다.

```text
effective TTL = min(기본 TTL + jitter, 가장 가까운 회차 시작까지 남은 시간)
```

회차 시작 경계에는 짧은 안전 여유를 두고 만료합니다. 캐시 적중률 일부를 양보하더라도 유효한 회차만 반환하는 정책을 우선합니다.

### 구조 원칙

단순 `@Cacheable`만으로 Redis 장애와 제한 fallback을 구분하면 cache get 오류가 발생한 모든 요청이 DB로 통과할 수 있습니다. 장애 정책을 명시적으로 제어하기 위해 다음 책임으로 분리합니다.

```text
EventQueryService
|-- EventCache            # 캐시 조회·저장 계약
|-- EventDatabaseReader   # 읽기 전용 DB 조회
`-- CacheAsideLoader      # 단일 로더와 제한된 DB fallback 제어
```

애플리케이션 계층은 `EventCache` 계약에 의존하고 Redis 연결·직렬화 구현은 infrastructure에 둡니다. 연결 설정, 공연 cache value 설정, fallback 제한을 각각 분리해 상태 Redis 추가 시 기존 캐시 구현의 변경을 최소화합니다.

### 트레이드오프

| 정책 | 얻는 것 | 비용·손실 | 현재 판단 |
| --- | --- | --- | --- |
| 캐시·상태 Redis 분리 | 메모리·eviction·장애 정책 격리 | 컨테이너와 운영 설정 증가 | 정합성 데이터의 임의 제거를 막기 위해 채택 |
| 캐시 영속성 비활성화 | 디스크 I/O와 복구 운영 제거 | 재시작 후 cold cache와 초기 DB 조회 | DB에서 재생성 가능하므로 채택 |
| `allkeys-lru` | 최근 조회된 공연 cache 유지 | 과거에 자주 조회됐더라도 최근 사용이 없으면 제거됨 | 공연의 시간 민감성과 최근성에 맞아 채택 |
| TTL jitter | 여러 key의 동시 만료 분산 | 만료 시각과 테스트가 비결정적 | 부하 분산을 위해 채택 |
| key별 단일 로더 | 같은 데이터의 중복 DB 조회 방지 | 대기와 락 만료 정책 필요 | stampede 방지를 위해 채택 |
| fallback 최대 `2` | DB와 예약·결제 경로 보호 | DB가 여유 있어도 일부 조회는 `503` | 전체 서비스 보호를 우선해 채택 |
| 최대 `300ms` 대기 | 빠른 재생성은 정상 응답으로 흡수 | 요청 스레드가 잠시 점유됨 | 장기 대기보다 짧은 제한 대기 채택 |
| 회차 경계 동적 TTL | 시작된 회차의 cache 잔존 방지 | 경계 시점 cache hit 감소 | 데이터 유효성을 우선해 채택 |

## 검증 결과

Redis 적용 후 다음 항목을 확인했습니다.

| 항목 | 확인 내용 |
| --- | --- |
| Redis key | `ticketledger:cache:event:list::all`, `ticketledger:cache:event:detail::{eventId}` 형태로 저장됨 |
| TTL | `event:list=60s`, `event:detail=5m` |
| 값 구조 | 목록 캐시는 `{"events":[...]}` wrapper DTO 형태로 저장됨 |
| Grafana | `ticketledger_event_cache_requests_total` 기반 hit/miss·Redis 오류·DB fallback·거부 패널 추가 |
| Redis Exporter | Redis 상태·메모리 사용률·연결 수·evicted key·명령 처리량 패널 추가 |
| k6 조회 테스트 | `20 VU / 30s`, 요청 `137,170`, 실패율 `0%`, 목록 p95 `6.76ms`, 상세 p95 `6.48ms` |

### Redis 장애 주입 결과

Redis 컨테이너만 중지하고 공연 목록·상세 조회를 `50 VU / 30s`로 실행했습니다. backend와 PostgreSQL은 유지하고 k6는 외부 도구 Compose에서 실행했습니다.

| 항목 | Redis timeout `2s` | Redis timeout `200ms` 후보 |
| --- | ---: | ---: |
| 전체 요청 | `800` | `7,234` |
| DB fallback 성공 | `264` (`33.00%`) | `5,715` (`79.00%`) |
| 보호 거부 `503` | `536` (`67.00%`) | `1,519` (`20.99%`) |
| 예기치 않은 응답 | `0` | `0` |
| 응답 p95 | `2.04s` | `225.66ms` |
| backend process CPU 최대 | `7.70%` | `83.49%` |
| Hikari pending 최대 | `0` | `0` |

두 조건 모두 `503`에 `Retry-After: 1`을 반환했고 테스트 후 backend liveness와 정상 조회가 복구됐습니다. fallback 세마포어 `2`는 Hikari connection 동시 점유는 제한했지만 DB fallback 처리율 자체는 제한하지 못했습니다. `200ms`는 요청 스레드 대기를 줄이는 대신 Redis 재시도와 DB fallback 처리량을 크게 늘려 CPU 보호 목표에 맞지 않아 채택하지 않았습니다.

따라서 운영 timeout `2s`는 이번 단계에서 유지하고, 반복 timeout을 줄이기 위한 Circuit Breaker를 적용했습니다. timeout 대기가 우연히 요청률을 낮추는 구조를 최종 정책으로 보지는 않으므로, 회로 적용 전후의 fail-fast 비율·DB fallback·CPU를 같은 장애 조건에서 다시 측정합니다. 캐시 재생성 대기 `300ms`와 refresh lock TTL `2s`는 Redis가 살아 있는 cache miss 경로의 값이므로 이번 Redis 중단 실험의 조정 대상에서 제외했습니다.

Circuit Breaker 적용 후 같은 `50 VU / 30s` 조건으로 재측정했습니다. 최초 실패 호출은 Redis 명령 timeout을 기다렸지만, 회로가 열린 뒤에는 Redis 호출을 차단하고 제한 DB fallback 또는 `503`으로 빠르게 전환했습니다.

| 항목 | Circuit Breaker 적용 후 |
| --- | ---: |
| 전체 요청 | `26,158` |
| DB fallback 성공 | `4,079` (`15.59%`) |
| 보호 거부 `503` | `22,079` (`84.40%`) |
| 예기치 않은 응답 | `0` |
| 응답 p95 | `248.59ms` |
| Circuit Breaker 차단 호출 | `26,116` |
| backend process CPU 최대 | `96.35%` |
| Hikari pending 최대 | `0` |

Redis 복구 후 회로는 `HALF_OPEN -> CLOSED`로 전환됐고, 목록·상세 조회 `200`, 캐시 key 재생성, backend liveness `200`을 확인했습니다. DB connection 대기는 발생하지 않았지만 fail-fast로 처리량이 늘면서 CPU가 포화됐으므로, 다음 조정의 기준은 fallback 동시성뿐 아니라 전체 요청률과 CPU 보호까지 포함해야 합니다.

별도의 격리 백엔드를 연결 불가능한 Redis 주소와 `connect-timeout=1s`로 기동한 cold connection 테스트에서는 첫 공연 조회가 약 `1.367s` 후 DB fallback `200`으로 끝났습니다. Redis 컨테이너 중단처럼 이미 만들어진 연결이 끊기는 경우에는 최초 호출이 command read timeout `2s`의 영향을 받고, 처음부터 연결이 성립하지 않는 경우에는 connect timeout이 먼저 적용됩니다.

## CI 통합

Redis 캐시 통합 테스트는 Testcontainers로 일회용 Redis를 실행합니다. Spring Boot의 `@ServiceConnection(name = "redis")`이 컨테이너의 동적 host와 port를 자동 주입하므로, 로컬 Redis 실행이나 CI의 고정 `localhost:6379` service container에 의존하지 않습니다.

## 후속 과제

- Redis 장애 시 backend CPU 상한을 기준으로 fallback 허용량과 상위 요청 제한 조정
- refresh token, 멱등성 key, 대기열, 분산락용 상태 Redis 정책 별도 설계
