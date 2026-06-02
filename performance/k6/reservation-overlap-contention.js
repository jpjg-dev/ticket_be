import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import exec from "k6/execution";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const caseName = __ENV.CASE_NAME || "reservation-overlap-contention";
const vus = Number(__ENV.VUS || 10);
const iterations = Number(__ENV.ITERATIONS || vus);
const cookie = __ENV.COOKIE;
const origin = __ENV.ORIGIN || "https://localhost:3000";
const seatPairs = (__ENV.SEAT_PAIRS || "")
  .split("|")
  .map((pair) =>
    pair
      .split(",")
      .map((seatId) => Number(seatId.trim()))
      .filter((seatId) => Number.isInteger(seatId) && seatId > 0)
  )
  .filter((pair) => pair.length >= 2);

const overlapDuration = new Trend("reservation_overlap_duration", true);
const overlapSuccess = new Counter("reservation_overlap_success");
const overlapRejected = new Counter("reservation_overlap_rejected");
const overlapUnexpected = new Counter("reservation_overlap_unexpected");
const overlapUnexpectedRate = new Rate("reservation_overlap_unexpected_rate");

if (!cookie) {
  throw new Error("COOKIE is required because reservation overlap contention needs authentication.");
}

if (seatPairs.length < 2) {
  throw new Error("SEAT_PAIRS must contain at least two overlapping pairs, e.g. 391,392|392,393.");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    reservation_overlap_contention: {
      executor: "shared-iterations",
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || "1m",
      exec: "createOverlapReservation",
      tags: {
        case: caseName,
        endpoint: "reservation-overlap-contention",
      },
    },
  },
  thresholds: {
    reservation_overlap_unexpected_rate: ["rate<0.01"],
  },
};

export function createOverlapReservation() {
  const pair = seatPairs[exec.scenario.iterationInTest % seatPairs.length];
  const response = http.post(
    `${baseUrl}/api/v1/reservations`,
    JSON.stringify({ seatIds: pair }),
    {
      headers: {
        "Content-Type": "application/json",
        Cookie: cookie,
        Origin: origin,
        Referer: `${origin}/`,
      },
      tags: {
        case: caseName,
        endpoint: "reservation-overlap-contention",
      },
    }
  );

  overlapDuration.add(response.timings.duration, { case: caseName });

  const isSuccess = response.status === 200;
  const isExpectedRejection = response.status >= 400 && response.status < 500;
  const isUnexpected = !isSuccess && !isExpectedRejection;

  if (isSuccess) {
    overlapSuccess.add(1, { case: caseName });
  } else if (isExpectedRejection) {
    overlapRejected.add(1, { case: caseName });
  } else {
    overlapUnexpected.add(1, { case: caseName });
    console.error(
      `unexpected overlap response status=${response.status} error=${response.error || ""} body=${response.body || ""}`
    );
  }

  overlapUnexpectedRate.add(isUnexpected, { case: caseName });

  check(
    response,
    {
      "overlap response is success or expected rejection": () => isSuccess || isExpectedRejection,
    },
    {
      case: caseName,
      endpoint: "reservation-overlap-contention",
    }
  );
}
