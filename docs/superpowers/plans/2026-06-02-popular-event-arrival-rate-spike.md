# Popular Event Arrival Rate Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local controlled arrival-rate E2E Spike with 10,000 distinct test users.

**Architecture:** Seed performance-only users with PostgreSQL, generate HS256 Access Token cookies with a Node utility, load them through k6 `SharedArray`, and run the existing booking-to-payment journey with a `ramping-arrival-rate` executor. Keep production code unchanged.

**Tech Stack:** PostgreSQL, PowerShell, Node.js built-in `crypto`, Grafana k6, Spring Boot YAML profiles.

---

### Task 1: Token Generator

**Files:**
- Create: `performance/scripts/generate-perf-user-tokens.test.js`
- Create: `performance/scripts/generate-perf-user-tokens.js`

- [ ] Write a failing Node contract test for HS256 payload, cookie shape, and expiration.
- [ ] Run the test and confirm failure because the module is missing.
- [ ] Implement the minimal token generator.
- [ ] Re-run the test and confirm pass.

### Task 2: Performance User Pool

**Files:**
- Create: `performance/sql/seed-perf-users.sql`
- Create: `performance/prepare-perf-user-pool.ps1`
- Modify: `.gitignore`

- [ ] Upsert `10,000` performance-only users.
- [ ] Export user IDs and generate `performance/data/perf-users.json`.
- [ ] Ignore generated performance data.

### Task 3: Controlled Performance Profile

**Files:**
- Create: `src/main/resources/application-perf.yaml`

- [ ] Disable SQL detail logs.
- [ ] Point Toss base URL to local Mock PG.
- [ ] Extend access token expiration and reservation hold duration for the measurement window.
- [ ] Prevent scheduler interference during the Spike.

### Task 4: Arrival Rate Scenario

**Files:**
- Create: `performance/k6/popular-event-payment-arrival-rate-spike.js`

- [ ] Load the token pool with `SharedArray`.
- [ ] Select one distinct user per `iterationInTest`.
- [ ] Add smoke and arrival profiles.
- [ ] Preserve journey, payment, contention, and unexpected-error metrics.

### Task 5: Verification And Measurement

- [ ] Run Node contract test and JS syntax checks.
- [ ] Generate the performance user pool.
- [ ] Start Mock PG and ask the user to run the backend with `dev,perf`.
- [ ] Run smoke, then arrival-rate Spike.
- [ ] Verify DB consistency with DB MCP.
- [ ] Record results in `docs/performance-test-strategy.md`.

