# 초기 E2E 병목 자원 분석

## 목적

초기 성능 개선 전 코드에서 발생한 지연이 단순한 서버 자원 한계인지, 애플리케이션 응답 경로가 자원 포화를 유발한 것인지 구분하기 위해 재측정했습니다.

이 측정은 기존 단계별 성능 비교표를 대체하지 않습니다. 당시 코드와 부하 조건을 다시 구성하고 OS/JVM/DB 지표를 함께 관찰한 원인 판별 실험입니다.

## 재현 조건

| 항목 | 조건 |
| --- | --- |
| 코드 | `7db0edb` (공연 캐시 도입 직전) |
| 부하 | `10 -> 100 -> 300 -> 500 -> 1,000 -> 100 RPS`, 총 `50초` |
| 데이터 | 성능 사용자 `10,000명`, 인기 회차 좌석 `1,000석` |
| 애플리케이션 | backend `4GB`, PostgreSQL `2GB`, 동일한 `2 vCPU` 공유 |
| 외부 부하 | k6와 Mock PG는 애플리케이션 CPU 영역에서 분리 |
| 계측 | Docker stats, Spring Boot Actuator, PostgreSQL `pg_stat_activity`, JFR |

초기 코드의 `perf` 프로필에는 CSRF 허용 Origin이 없어 테스트 Compose에서 `http://localhost:3000`만 허용했습니다. 비즈니스 로직과 조회 경로는 수정하지 않았습니다.

## 결과

### E2E

| 지표 | 측정값 |
| --- | ---: |
| HTTP 처리량 | `215.13 req/s` |
| HTTP p95 | `11.71s` |
| 전체 여정 p95 | `38.82s` |
| 좌석 조회 p95 | `12.06s` |
| 예약 생성 p95 | `12.36s` |
| 결제 준비 p95 | `11.93s` |
| 결제 승인 p95 | `11.80s` |
| dropped iteration | `13,027` |
| k6 관측 완료 결제 | `660` |
| 예상 밖 오류 | `0` |

테스트 종료 시 진행 중이던 요청이 서버에서 마무리된 뒤 DB에는 `APPROVED 797건`, `READY 166건`이 남았습니다. 좌석은 `BOOKED 797건`, `HELD 203건`이었고 중복 좌석, 부분 성공, 상태 불일치는 모두 `0건`이었습니다.

### 자원

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| backend CPU | `143.05%` | `178.29%` |
| PostgreSQL CPU | `24.60%` | `37.58%` |
| backend 메모리 | - | `1,222.7MiB` |
| backend PIDs | - | `248` |
| Hikari active | - | `10` |
| Hikari pending | - | `168` |
| PostgreSQL lock wait | - | `0` |
| PostgreSQL I/O wait | - | `0` |

Docker의 컨테이너 CPU 백분율은 할당된 두 CPU 전체를 합산하므로 backend 최대 `178.29%`는 두 CPU 중 약 `89%`를 사용한 상태입니다. Actuator에서 관찰한 system CPU도 평균 `91.2%`였습니다.

부하 구간의 누적 I/O 증가는 다음과 같았습니다.

- backend network: 수신 `937MB`, 송신 `340MB`
- PostgreSQL network: 수신 `16.4MB`, 송신 `974MB`
- backend block write: `13.4MB`
- PostgreSQL block write: `26MB`

디스크보다 애플리케이션과 DB 사이의 반복 데이터 전송, 응답 생성 비용이 크게 나타났습니다.

### JFR

- GC pause 총합은 `943ms`, p95는 `54.2ms`, 최대는 `55.9ms`였습니다.
- 할당 상위는 PostgreSQL 튜플 수신 `19.98%`, 문자열/배열 복사, Hibernate 엔티티 엔트리 생성 순서였습니다.
- 실행 샘플에는 Hibernate 영속성 컨텍스트 처리, PostgreSQL 결과 변환, Jackson 직렬화가 함께 나타났습니다.
- 장시간 JVM 중단이나 GC 실패는 없었습니다.

따라서 GC가 중심 병목이라는 근거는 없고, DB 결과를 엔티티로 구성하고 직렬화하는 반복 작업이 CPU와 메모리 할당을 소비한 것으로 판단했습니다.

## 판정

`A. 제한 자원의 CPU 포화`와 `B. 애플리케이션 응답 경로 병목`은 서로 배타적인 원인이 아니었습니다.

1. 직접적인 포화 지점은 backend CPU였습니다. PostgreSQL CPU와 lock/I/O wait는 상대적으로 낮았습니다.
2. CPU를 포화시킨 작업은 초기 코드의 반복 목록·상세·좌석 조회, Hibernate 엔티티 관리, JSON 직렬화와 내부 데이터 전송이었습니다.
3. CPU가 포화되자 요청 처리가 늦어지고 Hikari pending이 최대 `168`까지 증가했습니다. 커넥션 부족은 독립 원인이라기보다 앞선 처리 지연이 만든 2차 대기였습니다.
4. 메모리와 GC, 디스크는 이번 조건에서 우선 병목이 아니었습니다.

결론은 **제한 자원에서 애플리케이션의 반복 응답 비용이 backend CPU를 먼저 포화시키고, 이후 커넥션 대기와 전체 API 지연으로 확산됐다**입니다. 따라서 풀 크기 증설보다 반복 조회 제거, 캐시, 좌석 조회 트랜잭션 축소, SoldOut 정책을 먼저 적용한 기존 개선 순서가 계측 결과와 일치합니다.

## 계측 한계

- Actuator는 포화 구간에서 1초 scrape timeout이 발생해 정상 표본이 `5개`로 제한됐습니다.
- PostgreSQL 상태는 약 4초 간격으로 수집해 짧은 active query를 모두 포착하지 못합니다.
- JFR에는 애플리케이션 시작과 정상 종료 구간도 포함됩니다.
- Docker Desktop 위의 제한 자원 실험이므로 운영 절대 처리량으로 해석하지 않습니다.

## 산출물

테스트 산출물은 Git에 포함하지 않는 `ticket_infra/test-results/issue49/`에 보관했습니다.

- `issue49-initial-k6.json`
- `issue49-initial-resources.csv`
- `issue49-initial-postgres.csv`
- `issue49-initial-prometheus.txt`
- `issue49-initial.jfr`

## 참고 자료

- [Docker container stats](https://docs.docker.com/reference/cli/docker/container/stats/)
- [Spring Boot Actuator Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [Spring Boot Actuator Endpoints](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)
- [Java 21 Troubleshooting Guide](https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-guide.pdf)
- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
