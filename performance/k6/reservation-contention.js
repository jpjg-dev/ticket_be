import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const caseName = __ENV.CASE_NAME || "reservation-contention";
const vus = Number(__ENV.VUS || 10);
const iterations = Number(__ENV.ITERATIONS || vus);
const cookie = __ENV.COOKIE;
const origin = __ENV.ORIGIN || "https://localhost:3000";
const seatIds = (__ENV.SEAT_IDS || "")
  .split(",")
  .map((seatId) => Number(seatId.trim()))
  .filter((seatId) => Number.isInteger(seatId) && seatId > 0);

const contentionDuration = new Trend("reservation_contention_duration", true);
const contentionSuccess = new Counter("reservation_contention_success");
const contentionRejected = new Counter("reservation_contention_rejected");
const contentionUnexpected = new Counter("reservation_contention_unexpected");
const contentionUnexpectedRate = new Rate("reservation_contention_unexpected_rate");

if (!cookie) {
  throw new Error("COOKIE is required because reservation contention needs authentication.");
}

if (seatIds.length < 2) {
  throw new Error("SEAT_IDS must contain the same contention seat pair, e.g. 391,392.");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    reservation_contention: {
      executor: "shared-iterations",
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || "1m",
      exec: "createContentionReservation",
      tags: {
        case: caseName,
        endpoint: "reservation-contention",
      },
    },
  },
  thresholds: {
    reservation_contention_unexpected_rate: ["rate<0.01"],
  },
};

export function createContentionReservation() {
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
        endpoint: "reservation-contention",
      },
    }
  );

  contentionDuration.add(response.timings.duration, { case: caseName });

  const isSuccess = response.status === 200;
  const isExpectedRejection = response.status >= 400 && response.status < 500;
  const isUnexpected = !isSuccess && !isExpectedRejection;

  if (isSuccess) {
    contentionSuccess.add(1, { case: caseName });
  } else if (isExpectedRejection) {
    contentionRejected.add(1, { case: caseName });
  } else {
    contentionUnexpected.add(1, { case: caseName });
    console.error(
      `unexpected contention response status=${response.status} error=${response.error || ""} body=${response.body || ""}`
    );
  }

  contentionUnexpectedRate.add(isUnexpected, { case: caseName });

  check(
    response,
    {
      "contention response is success or expected rejection": () => isSuccess || isExpectedRejection,
    },
    {
      case: caseName,
      endpoint: "reservation-contention",
    }
  );
}
