import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";
import exec from "k6/execution";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const caseName = __ENV.CASE_NAME || "reservation-create-unique";
const vus = Number(__ENV.VUS || 1);
const iterations = Number(__ENV.ITERATIONS || 1);
const seatsPerRequest = Number(__ENV.SEATS_PER_REQUEST || 2);
const cookie = __ENV.COOKIE;
const origin = __ENV.ORIGIN || "https://localhost:3000";
const seatIds = (__ENV.SEAT_IDS || "")
  .split(",")
  .map((seatId) => Number(seatId.trim()))
  .filter((seatId) => Number.isInteger(seatId) && seatId > 0);

const reservationCreateDuration = new Trend("reservation_create_duration", true);
const reservationCreateFailed = new Rate("reservation_create_failed");

if (!cookie) {
  throw new Error("COOKIE is required because reservation create needs authentication.");
}

if (seatIds.length < iterations * seatsPerRequest) {
  throw new Error(
    `SEAT_IDS must contain at least iterations * seatsPerRequest ids. required=${iterations * seatsPerRequest}, actual=${seatIds.length}`
  );
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    reservation_create_baseline: {
      executor: "shared-iterations",
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || "1m",
      exec: "createReservation",
      tags: {
        case: caseName,
        endpoint: "reservation-create",
      },
    },
  },
  thresholds: {
    "checks{endpoint:reservation-create}": ["rate>0.99"],
    reservation_create_failed: ["rate<0.01"],
  },
};

export function createReservation() {
  const offset = exec.scenario.iterationInTest * seatsPerRequest;
  const requestSeatIds = seatIds.slice(offset, offset + seatsPerRequest);
  const response = http.post(
    `${baseUrl}/api/v1/reservations`,
    JSON.stringify({ seatIds: requestSeatIds }),
    {
      headers: {
        "Content-Type": "application/json",
        Cookie: cookie,
        Origin: origin,
        Referer: `${origin}/`,
      },
      tags: {
        case: caseName,
        endpoint: "reservation-create",
      },
    }
  );

  reservationCreateDuration.add(response.timings.duration, { case: caseName });
  reservationCreateFailed.add(response.status !== 200, { case: caseName });

  let body = null;
  if (response.status === 200) {
    body = response.json();
  } else {
    console.error(
      `reservation create failed status=${response.status} error=${response.error || ""} body=${response.body || ""}`
    );
  }

  check(
    response,
    {
      "reservation create returns 200": (result) => result.status === 200,
      "reservation group id exists": () => body !== null && Number.isInteger(body.reservationGroupId),
    },
    {
      case: caseName,
      endpoint: "reservation-create",
    }
  );
}
