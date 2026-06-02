# Popular Event Arrival Rate Spike Design

## Goal

단일 인기 공연 오픈 직후 신규 사용자 여정 유입률을 단계적으로 높여 로컬 통제 환경의 포화 구간, dropped iteration, 지연 증가, 결제 완료 처리량을 측정한다.

## Scope

- 대상 공연: `PERF_LOAD_TEST_EVENT`
- 대상 회차: `18`
- 대상 좌석: `LOAD-*`, 총 `1,000`석
- 테스트 사용자: `perf-user-00001@email.com`부터 `perf-user-10000@email.com`까지 `10,000`명
- PG: 외부 경량 Mock PG
- 대기열: 추가하지 않음

## Runtime Boundary

- 기존 `ramping-vus` E2E Spike는 정합성 baseline으로 유지한다.
- 신규 시나리오는 `ramping-arrival-rate` executor를 사용한다.
- 신규 `application-perf.yaml`은 SQL 상세 로그를 끄고 Mock PG 주소, 긴 좌석 hold 시간, 긴 스케줄러 주기를 고정한다.
- 백엔드는 `dev,perf` 프로필로 실행한다.
- 테스트 사용자 seed와 AT 생성은 `performance/prepare-perf-user-pool.ps1`에서 수행한다.
- 생성된 AT 파일 `performance/data/perf-users.json`은 Git에 올리지 않는다.

## User Pool

로그인 API를 `10,000`번 호출하지 않는다. 로그인 처리와 Refresh Token 저장 비용이 예약 E2E 측정에 섞이기 때문이다.

1. PostgreSQL `generate_series()`로 테스트 전용 사용자를 upsert한다.
2. Node.js 기본 `crypto` 모듈로 애플리케이션과 동일한 HS256 Access Token을 생성한다.
3. 사용자별 `userId`, `cookie`를 JSON 배열로 기록한다.
4. k6는 `SharedArray`로 JSON을 한 번만 읽고 `exec.scenario.iterationInTest`으로 사용자 데이터를 순차 할당한다.

## Arrival Rate

| Stage | Target | Duration |
| --- | ---: | ---: |
| Warm-up | `10 journeys/s` | `5s` |
| Low | `100 journeys/s` | `10s` |
| Normal | `300 journeys/s` | `10s` |
| High | `500 journeys/s` | `10s` |
| Peak | `1,000 journeys/s` | `10s` |
| Cool-down | `100 journeys/s` | `5s` |

예상 총 유입량은 약 `19,050 journeys`다.

## Metrics

- `dropped_iterations`
- 전체 여정 p95, p99
- 단계별 p95
- 결제 완료 건수와 `payments/s`
- 정상 경합 거부 건수
- 예상 밖 오류율
- DB 중복 active 좌석, 부분 성공 group, 상태 불일치

## Interpretation Boundary

결과는 로컬 통제 환경의 포화 구간과 개선 전후 비교 기준이다. 운영 서버의 처리량 보장 수치로 사용하지 않는다.

