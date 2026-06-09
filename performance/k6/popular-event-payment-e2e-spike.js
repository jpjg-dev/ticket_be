import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const caseName = __ENV.CASE_NAME || "popular-event-payment-e2e-spike";
const loadProfile = __ENV.LOAD_PROFILE || "smoke";
const cookie = __ENV.COOKIE;
const origin = __ENV.ORIGIN || "https://localhost:3000";
const eventTitle = __ENV.EVENT_TITLE || "PERF_LOAD_TEST_EVENT";
const scheduleId = Number(__ENV.SCHEDULE_ID || 18);
const minSeatId = Number(__ENV.MIN_SEAT_ID || 391);
const maxSeatId = Number(__ENV.MAX_SEAT_ID || 1390);

const journeyDuration = new Trend("e2e_journey_duration", true);
const eventListDuration = new Trend("e2e_event_list_duration", true);
const eventDetailDuration = new Trend("e2e_event_detail_duration", true);
const seatLookupDuration = new Trend("e2e_seat_lookup_duration", true);
const reservationDuration = new Trend("e2e_reservation_duration", true);
const paymentReadyDuration = new Trend("e2e_payment_ready_duration", true);
const paymentConfirmDuration = new Trend("e2e_payment_confirm_duration", true);
const reservationSuccess = new Counter("e2e_reservation_success");
const contentionRejected = new Counter("e2e_contention_rejected");
const paymentCompleted = new Counter("e2e_payment_completed");
const paymentCompletionRate = new Rate("e2e_payment_completion_rate");
const unexpected = new Counter("e2e_unexpected");
const unexpectedRate = new Rate("e2e_unexpected_rate");

if (!cookie) {
  throw new Error("COOKIE is required because the E2E journey creates reservations and payments.");
}

if (!["smoke", "spike"].includes(loadProfile)) {
  throw new Error("LOAD_PROFILE must be one of: smoke, spike.");
}

if (!Number.isInteger(scheduleId) || scheduleId <= 0) {
  throw new Error("SCHEDULE_ID must be a positive integer.");
}

if (minSeatId >= maxSeatId) {
  throw new Error("MIN_SEAT_ID must be less than MAX_SEAT_ID.");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: loadProfile === "smoke"
    ? {
        smoke: {
          executor: "shared-iterations",
          vus: 1,
          iterations: 1,
          maxDuration: "30s",
          exec: "completePopularEventPaymentJourney",
          tags: { case: caseName, profile: loadProfile },
        },
      }
    : {
        spike: {
          executor: "ramping-vus",
          startVUs: 1,
          stages: [
            { duration: "3s", target: 10 },
            { duration: "3s", target: 30 },
            { duration: "3s", target: 50 },
            { duration: "5s", target: 100 },
            { duration: "3s", target: 10 },
          ],
          gracefulRampDown: "5s",
          exec: "completePopularEventPaymentJourney",
          tags: { case: caseName, profile: loadProfile },
        },
      },
  thresholds: {
    e2e_unexpected_rate: ["rate<0.01"],
    e2e_payment_completion_rate: ["rate>0.99"],
  },
};

export function completePopularEventPaymentJourney() {
  const journeyStartedAt = Date.now();
  let isUnexpected = false;

  try {
    const eventsResponse = timedGet("/api/v1/event", eventListDuration, "event-list");
    if (!isSuccess(eventsResponse)) {
      return recordUnexpected("EVENT_LIST_FAILED", eventsResponse.status);
    }

    const events = parseJson(eventsResponse);
    const event = Array.isArray(events)
      ? events.find((candidate) => candidate.title === eventTitle)
      : null;
    if (!event) {
      return recordUnexpected("TARGET_EVENT_NOT_FOUND");
    }

    const detailResponse = timedGet(`/api/v1/event/${event.id}`, eventDetailDuration, "event-detail");
    if (!isSuccess(detailResponse)) {
      return recordUnexpected("EVENT_DETAIL_FAILED", detailResponse.status);
    }

    const detail = parseJson(detailResponse);
    const hasTargetSchedule = Array.isArray(detail && detail.schedules)
      && detail.schedules.some((schedule) => schedule.id === scheduleId);
    if (!hasTargetSchedule) {
      return recordUnexpected("TARGET_SCHEDULE_NOT_FOUND");
    }

    const seatsResponse = timedGet(
      `/api/v1/event/schedules/${scheduleId}/seats`,
      seatLookupDuration,
      "seat-list"
    );
    if (!isSuccess(seatsResponse)) {
      return recordUnexpected("SEAT_LOOKUP_FAILED", seatsResponse.status);
    }

    const seatList = parseJson(seatsResponse);
    if (seatList && seatList.soldOut) {
      contentionRejected.add(1, tags("SOLD_OUT"));
      return;
    }

    const seats = Array.isArray(seatList) ? seatList : seatList && Array.isArray(seatList.seats) ? seatList.seats : [];
    const availablePopularSeats = Array.isArray(seats)
      ? seats.filter((seat) => seat.status === "AVAILABLE"
          && seat.id >= minSeatId
          && seat.id <= maxSeatId)
      : [];
    if (availablePopularSeats.length === 0) {
      contentionRejected.add(1, tags("NO_AVAILABLE_POPULAR_SEAT"));
      return;
    }

    const selectedSeat = availablePopularSeats[Math.floor(Math.random() * availablePopularSeats.length)];
    const reservationResponse = timedPost(
      "/api/v1/reservations",
      { seatIds: [selectedSeat.id] },
      reservationDuration,
      "reservation"
    );

    if (isExpectedContention(reservationResponse)) {
      contentionRejected.add(1, tags("RESERVATION_CONFLICT"));
      return;
    }
    if (!isSuccess(reservationResponse)) {
      return recordUnexpected("RESERVATION_FAILED", reservationResponse.status);
    }

    reservationSuccess.add(1, tags());
    const reservation = parseJson(reservationResponse);
    if (!reservation || !reservation.reservationGroupId) {
      return recordPaymentFailure("INVALID_RESERVATION_RESPONSE");
    }

    const readyResponse = timedPost(
      "/api/v1/payments/ready",
      { reservationGroupId: reservation.reservationGroupId },
      paymentReadyDuration,
      "payment-ready"
    );
    if (!isSuccess(readyResponse)) {
      return recordPaymentFailure("PAYMENT_READY_FAILED", readyResponse.status);
    }

    const ready = parseJson(readyResponse);
    if (!ready || !ready.orderId || !Number.isInteger(ready.amount)) {
      return recordPaymentFailure("INVALID_PAYMENT_READY_RESPONSE");
    }

    const paymentKey = `perf-${__VU}-${__ITER}-${Date.now()}`;
    const confirmResponse = timedPost(
      "/api/v1/payments/confirm",
      { paymentKey, orderId: ready.orderId, amount: ready.amount },
      paymentConfirmDuration,
      "payment-confirm"
    );
    if (!isSuccess(confirmResponse)) {
      return recordPaymentFailure("PAYMENT_CONFIRM_FAILED", confirmResponse.status);
    }

    const confirmed = parseJson(confirmResponse);
    const isConfirmed = confirmed
      && confirmed.paymentStatus === "APPROVED"
      && confirmed.reservationStatus === "CONFIRMED"
      && confirmed.seatStatus === "BOOKED";
    if (!isConfirmed) {
      return recordPaymentFailure("INVALID_FINAL_STATE");
    }

    paymentCompleted.add(1, tags());
    paymentCompletionRate.add(true, tags());
  } catch (error) {
    isUnexpected = true;
    unexpected.add(1, tags("SCRIPT_EXCEPTION"));
    console.error(`reason=SCRIPT_EXCEPTION message=${error.message}`);
  } finally {
    journeyDuration.add(Date.now() - journeyStartedAt, tags());
    unexpectedRate.add(isUnexpected, tags());
  }

  function recordUnexpected(reason, status) {
    isUnexpected = true;
    unexpected.add(1, tags(reason, status));
  }

  function recordPaymentFailure(reason, status) {
    paymentCompletionRate.add(false, tags(reason, status));
    recordUnexpected(reason, status);
  }
}

function timedGet(path, metric, endpoint) {
  const response = http.get(`${baseUrl}${path}`, {
    tags: { case: caseName, endpoint },
  });
  metric.add(response.timings.duration, tags());
  return response;
}

function timedPost(path, body, metric, endpoint) {
  const response = http.post(`${baseUrl}${path}`, JSON.stringify(body), {
    headers: {
      "Content-Type": "application/json",
      Cookie: cookie,
      Origin: origin,
      Referer: `${origin}/`,
    },
    tags: { case: caseName, endpoint },
  });
  metric.add(response.timings.duration, tags());
  return response;
}

function parseJson(response) {
  try {
    return response.json();
  } catch {
    return null;
  }
}

function isSuccess(response) {
  return response.status === 200;
}

function isExpectedContention(response) {
  return response.status >= 400
    && response.status < 500
    && response.status !== 401
    && response.status !== 403;
}

function tags(reason, status) {
  const result = { case: caseName, profile: loadProfile };
  if (reason) {
    result.reason = reason;
  }
  if (status !== undefined) {
    result.status = String(status);
  }
  return result;
}
