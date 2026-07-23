import http from "k6/http";
import { sleep } from "k6";
import { SharedArray } from "k6/data";
import exec from "k6/execution";
import { Counter, Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const caseName = __ENV.CASE_NAME || "popular-event-payment-queue-arrival-rate-spike";
const loadProfile = __ENV.LOAD_PROFILE || "smoke";
const origin = __ENV.ORIGIN || "https://localhost:3000";
const eventTitle = __ENV.EVENT_TITLE || "PERF_LOAD_TEST_EVENT";
const scheduleId = Number(__ENV.SCHEDULE_ID || 18);
const minSeatId = Number(__ENV.MIN_SEAT_ID || 391);
const maxSeatId = Number(__ENV.MAX_SEAT_ID || 1390);
const selectionDelaySeconds = Number(__ENV.SELECTION_DELAY_SECONDS || 0.2);
const queueUsers = Number(__ENV.QUEUE_USERS || 1000);

const perfUsers = new SharedArray("performance users", () =>
  JSON.parse(open("../data/perf-users.json"))
);

const journeyDuration = new Trend("queue_e2e_journey_duration", true);
const queueRegistrationDuration = new Trend("queue_e2e_registration_duration", true);
const queueWaitDuration = new Trend("queue_e2e_wait_duration", true);
const reservationDuration = new Trend("queue_e2e_reservation_duration", true);
const paymentCompleted = new Counter("queue_e2e_payment_completed");
const contentionRejected = new Counter("queue_e2e_contention_rejected");
const unexpectedRate = new Rate("queue_e2e_unexpected_rate");

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: loadProfile === "smoke"
    ? {
        smoke: {
          executor: "shared-iterations",
          vus: 1,
          iterations: 1,
          maxDuration: "30s",
          exec: "completeQueuedPaymentJourney",
          tags: { case: caseName, profile: loadProfile },
        },
      }
    : loadProfile === "queue"
      ? {
          queue: {
            executor: "constant-arrival-rate",
            rate: Math.ceil(queueUsers / 10),
            timeUnit: "1s",
            duration: "10s",
            preAllocatedVUs: queueUsers,
            maxVUs: queueUsers,
            gracefulStop: "3m",
            exec: "completeQueuedPaymentJourney",
            tags: { case: caseName, profile: loadProfile },
          },
        }
      : loadProfile === "calibration"
        ? {
            calibration: {
              executor: "ramping-arrival-rate",
              startRate: 5,
              timeUnit: "1s",
              preAllocatedVUs: 500,
              maxVUs: 1000,
              stages: [
                { target: 5, duration: "10s" },
                { target: 10, duration: "10s" },
                { target: 15, duration: "15s" },
                { target: 20, duration: "15s" },
                { target: 30, duration: "10s" },
                { target: 5, duration: "10s" },
              ],
              gracefulStop: "2m",
              exec: "completeQueuedPaymentJourney",
              tags: { case: caseName, profile: loadProfile },
            },
          }
        : {
        arrival: {
          executor: "ramping-arrival-rate",
          startRate: 10,
          timeUnit: "1s",
          preAllocatedVUs: 1500,
          maxVUs: 3000,
          stages: [
            { target: 10, duration: "5s" },
            { target: 100, duration: "10s" },
            { target: 300, duration: "10s" },
            { target: 500, duration: "10s" },
            { target: 1000, duration: "10s" },
            { target: 100, duration: "5s" },
          ],
          gracefulStop: "4m",
          exec: "completeQueuedPaymentJourney",
          tags: { case: caseName, profile: loadProfile },
        },
      },
  thresholds: {
    queue_e2e_unexpected_rate: ["rate<0.01"],
  },
};

export function completeQueuedPaymentJourney() {
  const journeyStartedAt = Date.now();
  const user = perfUsers[exec.scenario.iterationInTest % perfUsers.length];
  let unexpected = false;

  try {
    const eventsResponse = get("/api/v1/event", "event-list");
    if (!isSuccess(eventsResponse)) return reject("EVENT_LIST_FAILED");

    const event = parseJson(eventsResponse)?.find((candidate) => candidate.title === eventTitle);
    if (!event) return reject("TARGET_EVENT_NOT_FOUND");

    const detailResponse = get(`/api/v1/event/${event.id}`, "event-detail");
    if (!isSuccess(detailResponse)) return reject("EVENT_DETAIL_FAILED");

    const registrationStartedAt = Date.now();
    const registrationResponse = post(
      "/api/v1/queue/admissions",
      { scheduleId },
      "queue-admission",
      user.cookie
    );
    queueRegistrationDuration.add(Date.now() - registrationStartedAt, tags());
    if (!isSuccess(registrationResponse)) return reject("QUEUE_REGISTRATION_FAILED");

    const admission = parseJson(registrationResponse);
    if (!admission || !["BYPASSED", "WAITING", "ADMITTED"].includes(admission.status)) {
      return reject("INVALID_QUEUE_RESPONSE");
    }

    let queueToken = admission.queueToken;
    if (admission.status === "WAITING") {
      const waitStartedAt = Date.now();
      const streamResponse = http.get(
        `${baseUrl}/api/v1/queue/admissions/stream?scheduleId=${scheduleId}&queueToken=${encodeURIComponent(queueToken)}`,
        {
          headers: authHeaders(user.cookie),
          timeout: "4m",
          tags: { case: caseName, endpoint: "queue-stream" },
        }
      );
      queueWaitDuration.add(Date.now() - waitStartedAt, tags());
      if (!isSuccess(streamResponse) || !streamResponse.body.includes('"status":"ADMITTED"')) {
        return reject(
          "QUEUE_ADMISSION_FAILED",
          `status=${streamResponse.status} body=${String(streamResponse.body).slice(0, 300)}`
        );
      }
    }

    sleep(selectionDelaySeconds);

    const seatsResponse = get(`/api/v1/event/schedules/${scheduleId}/seats`, "seat-list");
    if (!isSuccess(seatsResponse)) return reject("SEAT_LOOKUP_FAILED");

    const seatPayload = parseJson(seatsResponse);
    if (seatPayload?.soldOut) {
      contentionRejected.add(1, tags("SOLD_OUT"));
      return;
    }

    const seats = Array.isArray(seatPayload) ? seatPayload : seatPayload?.seats;
    const availableSeats = Array.isArray(seats)
      ? seats.filter((seat) => seat.status === "AVAILABLE" && seat.id >= minSeatId && seat.id <= maxSeatId)
      : [];
    if (availableSeats.length === 0) {
      contentionRejected.add(1, tags("NO_AVAILABLE_POPULAR_SEAT"));
      return;
    }

    const selectedSeat = availableSeats[Math.floor(Math.random() * availableSeats.length)];

    const reservationStartedAt = Date.now();
    const reservationResponse = post(
      "/api/v1/reservations",
      { scheduleId, queueToken, seatIds: [selectedSeat.id] },
      "reservation",
      user.cookie
    );
    reservationDuration.add(Date.now() - reservationStartedAt, tags());
    if ([409, 423].includes(reservationResponse.status)) {
      contentionRejected.add(1, tags("RESERVATION_CONFLICT"));
      return;
    }
    if (!isSuccess(reservationResponse)) return reject("RESERVATION_FAILED");

    const reservation = parseJson(reservationResponse);
    const readyResponse = post(
      "/api/v1/payments/ready",
      { reservationGroupId: reservation?.reservationGroupId },
      "payment-ready",
      user.cookie
    );
    if (!isSuccess(readyResponse)) return reject("PAYMENT_READY_FAILED");

    const ready = parseJson(readyResponse);
    const confirmResponse = post(
      "/api/v1/payments/confirm",
      {
        paymentKey: `perf-queue-${exec.scenario.iterationInTest}-${Date.now()}`,
        orderId: ready?.orderId,
        amount: ready?.amount,
      },
      "payment-confirm",
      user.cookie
    );
    if (!isSuccess(confirmResponse)) return reject("PAYMENT_CONFIRM_FAILED");

    const confirmed = parseJson(confirmResponse);
    if (confirmed?.paymentStatus !== "APPROVED"
        || confirmed?.reservationStatus !== "CONFIRMED"
        || confirmed?.seatStatus !== "BOOKED") {
      return reject("INVALID_FINAL_STATE");
    }
    paymentCompleted.add(1, tags());
  } catch (error) {
    reject("SCRIPT_EXCEPTION", error.message);
  } finally {
    journeyDuration.add(Date.now() - journeyStartedAt, tags());
    unexpectedRate.add(unexpected, tags());
  }

  function reject(reason, detail) {
    unexpected = true;
    console.error(`reason=${reason} detail=${detail || ""}`);
  }
}

function get(path, endpoint) {
  return http.get(`${baseUrl}${path}`, { tags: { case: caseName, endpoint } });
}

function post(path, body, endpoint, cookie) {
  return http.post(`${baseUrl}${path}`, JSON.stringify(body), {
    headers: authHeaders(cookie),
    tags: { case: caseName, endpoint },
  });
}

function authHeaders(cookie) {
  return {
    "Content-Type": "application/json",
    Cookie: cookie,
    Origin: origin,
    Referer: `${origin}/`,
  };
}

function isSuccess(response) {
  return response.status >= 200 && response.status < 300;
}

function parseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function tags(reason) {
  return reason ? { case: caseName, reason } : { case: caseName };
}
