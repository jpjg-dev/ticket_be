import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const scheduleId = __ENV.SCHEDULE_ID;
const expectedReleasedSeatIds = (__ENV.EXPECTED_RELEASED_SEAT_IDS || "")
  .split(",")
  .filter((value) => value.length > 0)
  .map((value) => Number(value.trim()));

const expirationFirstHitDuration = new Trend("expiration_first_hit_duration", true);
const expirationFirstHitFailed = new Rate("expiration_first_hit_failed");

if (!scheduleId) {
  throw new Error("SCHEDULE_ID is required.");
}

if (expectedReleasedSeatIds.length === 0 || expectedReleasedSeatIds.some((seatId) => Number.isNaN(seatId))) {
  throw new Error("EXPECTED_RELEASED_SEAT_IDS is required and must contain numeric seat IDs.");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    expiration_first_hit: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "10s",
    },
  },
  thresholds: {
    expiration_first_hit_failed: ["rate<0.01"],
    checks: ["rate>0.99"],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/v1/event/schedules/${scheduleId}/seats`, {
    tags: {
      case: "expiration-first-hit",
      endpoint: "seat-list",
    },
  });

  expirationFirstHitDuration.add(response.timings.duration);
  expirationFirstHitFailed.add(response.status !== 200);

  const seats = response.status === 200 ? response.json() : [];
  check(response, {
    "expiration first hit returns 200": (result) => result.status === 200,
    "expired held seats are available after lookup": () => expectedReleasedSeatIds.every((seatId) =>
      seats.some((seat) => seat.id === seatId && seat.status === "AVAILABLE")),
  });
}
