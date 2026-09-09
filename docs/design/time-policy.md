# 시간 저장 정책

TicketLedger는 실제로 발생한 시각과 공연 일정처럼 사람이 해석하는 로컬 시간을 분리해서 저장합니다.

## 기준

| 구분 | Java 타입 | DB 타입 | 기준 |
| --- | --- | --- | --- |
| 예약 생성·만료, 결제 요청/승인/취소, 토큰 발급/만료, **예매 오픈** | `Instant` | `TIMESTAMP WITH TIME ZONE` | UTC 절대 시점 |
| 회차 시작/종료 | `LocalDateTime` | `TIMESTAMP WITHOUT TIME ZONE` | 공연장 로컬 벽시계 |

## 실제 발생 시각

예약, 결제, 인증 토큰처럼 "언제 발생했는가"가 중요한 값은 `Instant`로 다룹니다.
DB에는 `TIMESTAMP WITH TIME ZONE`으로 저장합니다. 전역 `hibernate.jdbc.time_zone`은 사용하지 않습니다. 이 설정은 `TIMESTAMP WITHOUT TIME ZONE`으로 저장한 회차 `LocalDateTime`까지 변환할 수 있어 공연장 로컬 벽시계 시간이 어긋날 수 있기 때문입니다.

대상 예시:

- `ReservationGroup.createdAt`, `ReservationGroup.expiresAt`
- `Reservation.reservedAt`, `Reservation.expiresAt`, `Reservation.canceledAt`
- `Payment.requestedAt`, `Payment.confirmingAt`, `Payment.approvedAt`, `Payment.canceledAt`
- `RefreshToken.createdAt`, `RefreshToken.expiresAt`, `RefreshToken.lastUsedAt`, `RefreshToken.revokedAt`
- `User.createdAt`, `User.updatedAt`
- `Event.createdAt`, `Schedule.createdAt`, `Seat.createdAt`
- `Event.bookingOpenAt` — 예매 오픈은 "전 세계 동일한 한 순간"이라 절대 시점(`Instant`)으로 둔다. `Instant.now()`와 직접 비교하므로 타임존 버그가 원천적으로 불가능하다.

API에서 이 값들은 UTC 기준 ISO-8601 시각으로 전달하고, 화면 표시는 프론트엔드가 `Asia/Seoul` 기준으로 변환합니다.

## 도메인 상태 전이 시각

`Payment`와 `Reservation` 도메인은 시스템 시계를 직접 조회하지 않습니다. 운영 애플리케이션 서비스가 주입받은 `Clock`에서 `Instant`를 샘플링하고 상태 전이 메서드에 명시적으로 전달합니다.

현재 적용 범위:

- `Payment.confirmingAt`, `Payment.approvedAt`, `Payment.cancelingAt`, `Payment.canceledAt`
- `Reservation.canceledAt`
- `Payment`, `ReservationGroup`, `Reservation` 생성 시각과 만료 시각

상태 진입 시각인 `confirmingAt`과 `cancelingAt`은 필요한 행 락을 획득한 뒤 PG 호출 전에 중간 상태를 남기는 위치에서 샘플링합니다. 완료 시각인 `approvedAt`, `Payment.canceledAt`, `Reservation.canceledAt`은 PG 응답 또는 보정 조회가 끝나고 행 락을 다시 획득한 뒤 실제 상태 전이를 적용하는 위치에서 샘플링합니다. 승인 진행 및 보정 승인에서는 만료 검증과 상태 기록에 같은 `Instant`를 사용합니다. 같은 취소 완료 트랜잭션에서 변경되는 `Payment`와 `Reservation`에도 하나의 `Instant`를 전달합니다.

절대 시점 도메인 생성자는 `Instant`만 받습니다. `LocalDateTime`을 JVM 기본 타임존(`ZoneId.systemDefault()`)으로 변환하던 레거시 오버로드는 실행 환경에 따라 결과가 달라질 수 있어 제거했습니다.

개발용 `DataInitializer`의 과거 이력 fixture는 실행 시작 시 한 번 만든 기준 `Instant`를 결제 요청·승인 진행·승인 완료·취소 이력에 재사용합니다. 이는 운영 상태 전이 경로가 아니라 개발 조회용 데이터의 내부 시각 일관성을 위한 변경입니다.

공식 근거: [Java SE 21 `Clock`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html)은 현재 시각이 필요한 코드에 `Clock`을 주입하면 `fixed` 같은 대체 시계를 사용해 테스트를 결정적으로 만들 수 있다고 설명하며, 기본 타임존 의존은 피하도록 권고합니다.

## 공연 일정 시각

회차 시작/종료 시간은 특정 instant 기록보다 "공연장 현지 기준 몇 시 공연인가"가 중요하므로 `LocalDateTime`으로 둡니다.
"지금 시작했는가" 같은 비교는 **서비스 타임존**(`app.time.service-zone`, 기본 `Asia/Seoul`)을 코드에서 명시(`LocalDateTime.now(zone)`)해 수행하며, 컨테이너/JVM 기본 타임존에 의존하지 않습니다.

대상:

- `Schedule.startAt`
- `Schedule.endAt`

> 같은 "시간 값"이라도 예매 오픈(절대 시점 → `Instant`)과 회차 시작(현지 벽시계 → `LocalDateTime`)은 의미가 달라 타입이 갈립니다.

향후 해외 공연장을 지원하면 단일 `service-zone` 대신 **공연장(venue)별 timezone 컬럼**을 추가하고, 시작/종료 비교·표시를 venue 존 기준으로 확장합니다. 이때도 시작 시간은 Instant가 아니라 `LocalDateTime` + venue 존으로 두는 것이 정석입니다(미래의 "벽시계 약속"은 DST·타임존 룰 변경에도 현지 시각이 유지돼야 하므로).

## 기존 데이터 마이그레이션

기존 `TIMESTAMP WITHOUT TIME ZONE` 데이터는 한국 시간으로 저장된 값으로 보고 `Asia/Seoul` 기준 시각을 UTC instant로 변환합니다.
이를 위해 `V4__convert_event_occurrence_times_to_timestamptz.sql`에서 Instant 대상 컬럼(각 테이블 `created_at`, 예약/결제/토큰 시각, **`events.booking_open_at`**)만 `TIMESTAMP WITH TIME ZONE`으로 변경합니다. 회차 `start_at`/`end_at`는 공연장 로컬 시간이라 그대로 둡니다.

> 이미 운영에 적용된 `V1`은 절대 수정하지 않습니다(Flyway 체크섬 불일치로 앱 기동 실패). 스키마 변경은 새 마이그레이션(V4)으로만 합니다.
