import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const scheduleId = __ENV.SCHEDULE_ID;
const caseName = __ENV.CASE_NAME || "no-expired";
const vus = Number(__ENV.VUS || 10);
const duration = __ENV.DURATION || "30s";

const seatLookupDuration = new Trend("seat_lookup_duration", true);
const seatLookupFailed = new Rate("seat_lookup_failed");

if (!scheduleId) {
  throw new Error("SCHEDULE_ID is required. Example: SCHEDULE_ID=1 k6 run performance/k6/seats-baseline.js");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    seats_baseline: {
      executor: "constant-vus",
      vus,
      duration,
      exec: "querySeats",
      tags: {
        case: caseName,
        endpoint: "seat-list",
      },
    },
  },
  thresholds: {
    "checks{endpoint:seat-list}": ["rate>0.99"],
    seat_lookup_failed: ["rate<0.01"],
  },
};

export function querySeats() {
  const response = http.get(`${baseUrl}/api/v1/event/schedules/${scheduleId}/seats`, {
    tags: {
      case: caseName,
      endpoint: "seat-list",
    },
  });

  seatLookupDuration.add(response.timings.duration, { case: caseName });
  seatLookupFailed.add(response.status !== 200, { case: caseName });

  check(response, {
    "seat lookup returns 200": (result) => result.status === 200,
  }, {
    case: caseName,
    endpoint: "seat-list",
  });
}
