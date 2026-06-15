# Reservation Hold Duration Centralization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hardcoded reservation hold expiration with an environment-configured duration while ensuring every reservation in one group shares the same expiry timestamp.

**Architecture:** Inject `reservation.hold-duration` directly into `ReservationService` as a `Duration`. `ReservationService` calculates one `expiresAt` value at reservation creation and passes it to `ReservationGroup` and every child `Reservation`, keeping group-level and row-level data consistent until the later group-status redesign.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring `@Value`, JUnit 5, Mockito.

---

### Task 1: Express the Expiration Contract in Tests

**Files:**
- Modify: `src/test/java/com/jipi/ticket_ledger/reservation/application/ReservationServiceTest.java`

- [ ] Add assertions proving a configured duration determines `ReservationGroup.expiresAt`.
- [ ] Add assertions proving saved child reservations reuse the group's exact `expiresAt`.
- [ ] Run `.\gradlew.bat test --tests "com.jipi.ticket_ledger.reservation.application.ReservationServiceTest"` and confirm the new expiration expectation does not pass under the hardcoded entity behavior.

### Task 2: Add Configured Hold Duration to Reservation Creation

**Files:**
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/application/ReservationService.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/domain/ReservationGroup.java`
- Modify: `src/main/java/com/jipi/ticket_ledger/reservation/domain/Reservation.java`

- [ ] Inject `reservation.hold-duration` as a `Duration` field in `ReservationService`.
- [ ] Compute `LocalDateTime expiresAt = now.plus(holdDuration)` once in `createReservation`.
- [ ] Pass `now` and `expiresAt` into the group and child reservation constructors.
- [ ] Remove `now.plusSeconds(30)` and the unused `RESERVATION_HOLD_MINUTES` constant.

### Task 3: Update Configuration and Test Fixtures

**Files:**
- Modify: `src/main/resources/application-dev.yaml`
- Modify: `src/main/resources/application-prod.yaml`
- Modify: `src/main/resources/application-test.yaml`
- Modify: reservation/payment/user test fixture sources that instantiate `ReservationGroup` and `Reservation`.

- [ ] Configure `reservation.hold-duration: 30s` for development and tests.
- [ ] Configure `reservation.hold-duration: 5m` for production.
- [ ] Update test fixtures to explicitly supply expiration timestamps compatible with the new constructor contract.

### Task 4: Verify and Track Outcome

**Files:**
- Modify: `docs/planning/backend-issue-list.md`
- Modify: `docs/planning/backend-refactoring-list.md` only where the completed hardcoded-duration concern is no longer unresolved.

- [ ] Run reservation and payment tests affected by constructor and service changes.
- [ ] Run the backend test suite if focused tests pass.
- [ ] Record completion in issue tracking while preserving later group-state and time-policy work as unresolved.
