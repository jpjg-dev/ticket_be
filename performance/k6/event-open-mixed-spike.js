import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const caseName = __ENV.CASE_NAME || "event-open-mixed-spike";
const scheduleId = __ENV.SCHEDULE_ID || "18";
const cookie = __ENV.COOKIE;
const origin = __ENV.ORIGIN || "https://localhost:3000";
const minSeatId = Number(__ENV.MIN_SEAT_ID || 391);
const maxSeatId = Number(__ENV.MAX_SEAT_ID || 1390);
const singleSeatRatio = Number(__ENV.SINGLE_SEAT_RATIO || 0.4);

const seatLookupDuration = new Trend("event_open_seat_lookup_duration", true);
const seatLookupUnexpectedRate = new Rate("event_open_seat_lookup_unexpected_rate");
const reservationDuration = new Trend("event_open_reservation_duration", true);
const reservationSuccess = new Counter("event_open_reservation_success");
const reservationRejected = new Counter("event_open_reservation_rejected");
const reservationUnexpected = new Counter("event_open_reservation_unexpected");
const reservationUnexpectedRate = new Rate("event_open_reservation_unexpected_rate");

if (!cookie) {
  throw new Error("COOKIE is required because mixed spike contains reservation requests.");
}

if (minSeatId >= maxSeatId) {
  throw new Error("MIN_SEAT_ID must be less than MAX_SEAT_ID.");
}

const baseStages = [
  { duration: "3s", target: 10 },
  { duration: "3s", target: 100 },
  { duration: "3s", target: 300 },
  { duration: "3s", target: 500 },
  { duration: "3s", target: 1000 },
  { duration: "3s", target: 1500 },
  { duration: "3s", target: 2000 },
  { duration: "5s", target: 10 },
];

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    seat_lookup_spike: {
      executor: "ramping-vus",
      startVUs: 7,
      stages: scaleStages(0.7),
      gracefulRampDown: "3s",
      exec: "querySeats",
      tags: {
        case: caseName,
        endpoint: "event-open-seat-list",
      },
    },
    reservation_random_spike: {
      executor: "ramping-vus",
      startVUs: 3,
      stages: scaleStages(0.3),
      gracefulRampDown: "3s",
      exec: "createRandomReservation",
      tags: {
        case: caseName,
        endpoint: "event-open-reservation",
      },
    },
  },
  thresholds: {
    event_open_seat_lookup_unexpected_rate: ["rate<0.01"],
    event_open_reservation_unexpected_rate: ["rate<0.01"],
  },
};

export function querySeats() {
  const response = http.get(`${baseUrl}/api/v1/event/schedules/${scheduleId}/seats`, {
    tags: {
      case: caseName,
      endpoint: "event-open-seat-list",
    },
  });

  const isUnexpected = response.status !== 200;
  seatLookupDuration.add(response.timings.duration, { case: caseName });
  seatLookupUnexpectedRate.add(isUnexpected, { case: caseName });

  check(
    response,
    {
      "event open seat lookup returns 200": () => response.status === 200,
    },
    {
      case: caseName,
      endpoint: "event-open-seat-list",
    }
  );
}

export function createRandomReservation() {
  const seatIds = randomSeatIds();
  const response = http.post(
    `${baseUrl}/api/v1/reservations`,
    JSON.stringify({ seatIds }),
    {
      headers: {
        "Content-Type": "application/json",
        Cookie: cookie,
        Origin: origin,
        Referer: `${origin}/`,
      },
      tags: {
        case: caseName,
        endpoint: "event-open-reservation",
      },
    }
  );

  const isSuccess = response.status === 200;
  const isExpectedRejection = response.status >= 400 && response.status < 500
    && response.status !== 401
    && response.status !== 403;
  const isUnexpected = !isSuccess && !isExpectedRejection;

  reservationDuration.add(response.timings.duration, { case: caseName });

  if (isSuccess) {
    reservationSuccess.add(1, { case: caseName });
  } else if (isExpectedRejection) {
    reservationRejected.add(1, { case: caseName });
  } else {
    reservationUnexpected.add(1, { case: caseName, status: String(response.status) });
  }

  reservationUnexpectedRate.add(isUnexpected, { case: caseName });

  check(
    response,
    {
      "event open reservation is success or expected rejection": () => isSuccess || isExpectedRejection,
    },
    {
      case: caseName,
      endpoint: "event-open-reservation",
    }
  );
}

function randomSeatIds() {
  const firstSeatId = randomSeatId();
  if (Math.random() < singleSeatRatio) {
    return [firstSeatId];
  }

  let secondSeatId = randomSeatId();
  while (secondSeatId === firstSeatId) {
    secondSeatId = randomSeatId();
  }

  return [firstSeatId, secondSeatId].sort((left, right) => left - right);
}

function randomSeatId() {
  return Math.floor(Math.random() * (maxSeatId - minSeatId + 1)) + minSeatId;
}

function scaleStages(ratio) {
  return baseStages.map((stage) => ({
    duration: stage.duration,
    target: Math.max(1, Math.round(stage.target * ratio)),
  }));
}
