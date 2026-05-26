# Reservation Expiration Trigger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예약 만료 상태 전이를 전용 서비스로 분리하고 좌석 조회에서는 현재 회차만 즉시 정리한다.

**Architecture:** `ReservationExpirationService`가 만료 상태 전이를 단일 책임으로 소유한다. 스케줄러는 전체 만료를, `EventService.getSeats()`는 회차별 만료를 호출하고, `ReservationService.createReservation()`은 선점 생성 책임만 유지한다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito

---

### Task 1: 만료 서비스 계약 테스트

**Files:**
- Create: `src/test/java/com/jipi/ticket_ledger/reservation/application/ReservationExpirationServiceTest.java`
- Modify: `src/test/java/com/jipi/ticket_ledger/reservation/application/ReservationServiceTest.java`

- [x] 전체 만료 상태 전이와 회차별 조회 계약을 `ReservationExpirationServiceTest`에 작성한다.
- [x] 예약 생성 테스트에 전체 만료 repository를 호출하지 않는 검증을 추가한다.
- [x] 해당 테스트를 실행해 신규 서비스 부재 또는 기존 전체 만료 호출 때문에 실패함을 확인한다.

### Task 2: 만료 서비스와 repository 조회 분리

**Files:**
- Create: `src/main/java/com/jipi/ticket_ledger/reservation/application/ReservationExpirationService.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/domain/ReservationGroupRepository.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/application/ReservationService.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/application/ReservationExpirationScheduler.java`

- [x] 기존 상태 전이 코드를 `ReservationExpirationService.expireAll()`로 옮긴다.
- [x] `scheduleId`와 만료 시각으로 대상 group을 제한하는 JPA 조회를 추가한다.
- [x] `expireByScheduleId()`가 공통 상태 전이 처리 메서드를 재사용하도록 구현한다.
- [x] 스케줄러 호출을 `expireAll()`로 변경하고 예약 생성의 전체 만료 호출을 제거한다.

### Task 3: 좌석 조회 연계와 문서 정리

**Files:**
- Create: `src/test/java/com/jipi/ticket_ledger/event/application/EventServiceTest.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/event/application/EventService.java`
- Modify: `docs/TestCase.md`
- Modify: `docs/backend-issue-list.md`

- [x] 좌석 조회가 회차별 만료 정리를 먼저 호출하는 테스트를 작성한다.
- [x] `getSeats()`를 쓰기 트랜잭션으로 재정의하고 회차별 정리 호출을 추가한다.
- [x] 테스트 문서의 만료 처리 담당 서비스를 변경하고, 해결된 수동 트리거 이슈를 제거한다.
- [x] 관련 단위 테스트를 실행해 결과를 검증한다.
