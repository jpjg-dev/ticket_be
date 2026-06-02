import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const scheduleId = __ENV.SCHEDULE_ID;
const vus = Number(__ENV.VUS || 20);
const expectedReleasedSeatIds = (__ENV.EXPECTED_RELEASED_SEAT_IDS || "")
  .split(",")
  .filter((value) => value.length > 0)
  .map((value) => Number(value.trim()));

const expirationConcurrentDuration = new Trend("expiration_concurrent_duration", true);
const expirationConcurrentFailed = new Rate("expiration_concurrent_failed");
const expirationConcurrentUnexpectedSeatState = new Counter("expiration_concurrent_unexpected_seat_state");

if (!scheduleId) {
  throw new Error("SCHEDULE_ID is required.");
}

if (expectedReleasedSeatIds.length === 0 || expectedReleasedSeatIds.some((seatId) => Number.isNaN(seatId))) {
  throw new Error("EXPECTED_RELEASED_SEAT_IDS is required and must contain numeric seat IDs.");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    expiration_concurrent_lookup: {
      executor: "per-vu-iterations",
      vus,
      iterations: 1,
      maxDuration: "30s",
    },
  },
  thresholds: {
    expiration_concurrent_failed: ["rate<0.01"],
    expiration_concurrent_unexpected_seat_state: ["count==0"],
    checks: ["rate>0.99"],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/v1/event/schedules/${scheduleId}/seats`, {
    tags: {
      case: "expiration-concurrency",
      endpoint: "seat-list",
    },
  });

  expirationConcurrentDuration.add(response.timings.duration);
  expirationConcurrentFailed.add(response.status !== 200);

  const seats = response.status === 200 ? response.json() : [];
  const seatsReleased = expectedReleasedSeatIds.every((seatId) =>
    seats.some((seat) => seat.id === seatId && seat.status === "AVAILABLE"));

  if (!seatsReleased) {
    expirationConcurrentUnexpectedSeatState.add(1);
  }

  check(response, {
    "concurrent expiration lookup returns 200": (result) => result.status === 200,
    "concurrent lookups observe released seats": () => seatsReleased,
  });
}
