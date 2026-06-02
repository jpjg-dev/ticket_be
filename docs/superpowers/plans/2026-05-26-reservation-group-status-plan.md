# Reservation Group Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예매 묶음의 대표 상태를 저장하고 마이페이지 노출 기준을 group 상태로 전환한다.

**Architecture:** `ReservationGroup`은 묶음 상태를 소유하고, 결제 승인/취소 및 만료 서비스가 하위 상태 전이와 동일 트랜잭션에서 group도 변경한다. 개별 `Reservation` 상태는 내부 정합성 검증과 좌석 상세 매핑을 위해 유지한다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito

---

### Task 1: 상태 전이 테스트 작성

**Files:**
- Modify: `src/test/java/com/jipi/ticket_ledger/reservation/application/ReservationServiceTest.java`
- Modify: `src/test/java/com/jipi/ticket_ledger/reservation/application/ReservationExpirationServiceTest.java`
- Modify: `src/test/java/com/jipi/ticket_ledger/payment/application/PaymentServiceTest.java`
- Modify: `src/test/java/com/jipi/ticket_ledger/user/application/UserServiceTest.java`

- [x] Group 생성, 승인, 취소, 만료 상태 기대값을 테스트에 추가한다.
- [x] 마이페이지 예매 조회가 group 상태 기준 repository 계약을 사용하는 테스트로 변경한다.
- [x] 테스트를 실행해 신규 group 상태 API 부재로 실패함을 확인한다.

### Task 2: Group 상태 모델 및 상태 전이 구현

**Files:**
- Create: `src/main/java/com/jipi/ticket_ledger/reservation/domain/ReservationGroupStatus.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/domain/ReservationGroup.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/payment/application/PaymentService.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/application/ReservationExpirationService.java`

- [x] Group 생성 시 `PENDING`을 저장한다.
- [x] 승인/취소/만료 메서드를 group 엔티티에 추가한다.
- [x] 결제 승인/취소와 만료 흐름에서 group 상태를 같은 트랜잭션으로 전이한다.
- [x] 결제 실패이지만 미만료인 group은 `PENDING`으로 유지한다.

### Task 3: 마이페이지 기준 전환 및 검증

**Files:**
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/domain/ReservationRepository.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/user/application/UserService.java`
- Modify: `docs/TestCase.md`
- Modify: `docs/backend-issue-list.md`

- [x] 마이페이지 예매 목록 조회 조건을 `ReservationGroup.status` 기준으로 변경한다.
- [x] 예매 DTO 상태를 group 상태에서 반환한다.
- [x] 테스트와 이슈 문서를 현행 상태 모델 기준으로 갱신한다.
- [x] 관련 테스트를 실행해 검증한다.

### Task 4: 운영 스키마 반영

**Files:**
- Modify: `build.gradle`
- Create: `src/main/resources/db/migration/V1__init_schema.sql`
- Modify: `src/main/resources/application-dev.yaml`
- Modify: `src/main/resources/application-prod.yaml`

- [x] Flyway와 PostgreSQL 지원 모듈 의존성을 추가한다.
- [x] 현재 전체 스키마 및 `reservation_groups.status`를 포함한 `V1__init_schema.sql`을 작성한다.
- [x] `IDENTITY` 컬럼과 중복되는 추출 sequence 생성문을 제거한다.
- [x] 개발 DB에서 Flyway `V1` 적용과 Hibernate `validate` 기동을 확인한다.
- [x] 운영 DB를 신규 스키마 기준으로 초기화하고 Flyway `V1` 반영 및 애플리케이션 기동을 확인한다.
