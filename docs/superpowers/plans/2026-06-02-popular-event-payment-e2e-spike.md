# Popular Event Payment E2E Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a test-only external Mock PG and a k6 scenario that measures the popular-event journey from browsing to confirmed payment.

**Architecture:** Keep production payment code unchanged. Run a small Node.js Mock PG as an external process, override the Toss base URL only through `application-test.yaml`, and call the existing backend APIs from k6.

**Tech Stack:** Node.js built-in `http`, Node.js built-in test runner, Grafana k6, Spring Boot YAML profiles, PostgreSQL verification through DB MCP.

---

### Task 1: Mock PG Contract Test

**Files:**
- Create: `performance/mock-pg/mock-pg-server.test.js`
- Create: `performance/mock-pg/mock-pg-server.js`

- [ ] Write a Node test for health, successful confirmation, invalid body rejection, and idempotent replay.
- [ ] Run `node --test performance/mock-pg/mock-pg-server.test.js` and confirm it fails because the server module does not exist.
- [ ] Implement the minimal external Mock PG server with Node built-in modules.
- [ ] Re-run the Node test and confirm all cases pass.

### Task 2: Test Profile Override

**Files:**
- Modify: `src/main/resources/application-test.yaml`

- [ ] Add `toss.payments.base-url: http://127.0.0.1:18080`.
- [ ] Keep the default Toss URL in `application.yaml` unchanged.

### Task 3: Popular Event Payment E2E Spike

**Files:**
- Create: `performance/k6/popular-event-payment-e2e-spike.js`

- [ ] Add a single `ramping-vus` user-journey scenario.
- [ ] Measure the complete journey and each HTTP step with custom `Trend` metrics.
- [ ] Separate expected seat-contention rejections from authentication failures, server errors, parsing errors, payment failures, and final-state mismatches.
- [ ] Generate a unique Mock `paymentKey` per successful reservation.
- [ ] Validate the final `APPROVED / CONFIRMED / BOOKED` response.

### Task 4: Documentation

**Files:**
- Modify: `docs/performance-test-strategy.md`
- Modify: `docs/private/session-handoff-2026-06-02.md`

- [ ] Document the Mock PG startup command.
- [ ] Document that the backend must run with `dev,test` profiles for this E2E only.
- [ ] Document the k6 smoke command and the DB MCP post-verification conditions.

### Task 5: Verification

- [ ] Run `node --test performance/mock-pg/mock-pg-server.test.js`.
- [ ] Run `node --check performance/mock-pg/mock-pg-server.js`.
- [ ] Run `node --check performance/k6/popular-event-payment-e2e-spike.js`.
- [ ] Run `git diff --check`.
- [ ] Ask the user to restart the already-managed backend with `dev,test`, then request a fresh AT before k6 execution.

