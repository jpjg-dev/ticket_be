import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import exec from "k6/execution";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const caseName = __ENV.CASE_NAME || "reservation-spike";
const mode = __ENV.MODE || "same";
const cookie = __ENV.COOKIE;
const origin = __ENV.ORIGIN || "https://localhost:3000";
const minSeatId = Number(__ENV.MIN_SEAT_ID || 391);
const maxSeatId = Number(__ENV.MAX_SEAT_ID || 1390);
const singleSeatRatio = Number(__ENV.SINGLE_SEAT_RATIO || 0.4);
const sameSeatIds = parseSeatIds(__ENV.SEAT_IDS || "391,392");
const overlapPairs = (__ENV.SEAT_PAIRS || "391,392|392,393")
  .split("|")
  .map(parseSeatIds)
  .filter((pair) => pair.length >= 2);

const spikeDuration = new Trend("reservation_spike_duration", true);
const spikeSuccess = new Counter("reservation_spike_success");
const spikeRejected = new Counter("reservation_spike_rejected");
const spikeUnexpected = new Counter("reservation_spike_unexpected");
const spikeUnexpectedRate = new Rate("reservation_spike_unexpected_rate");

if (!cookie) {
  throw new Error("COOKIE is required because reservation spike needs authentication.");
}

if (!["same", "overlap", "random"].includes(mode)) {
  throw new Error("MODE must be one of: same, overlap, random.");
}

if (minSeatId >= maxSeatId) {
  throw new Error("MIN_SEAT_ID must be less than MAX_SEAT_ID.");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    reservation_spike: {
      executor: "ramping-vus",
      startVUs: 10,
      stages: [
        { duration: "3s", target: 10 },
        { duration: "3s", target: 100 },
        { duration: "3s", target: 300 },
        { duration: "3s", target: 500 },
        { duration: "3s", target: 1000 },
        { duration: "3s", target: 1500 },
        { duration: "3s", target: 2000 },
        { duration: "5s", target: 10 },
      ],
      gracefulRampDown: "3s",
      exec: "createSpikeReservation",
      tags: {
        case: caseName,
        endpoint: "reservation-spike",
        mode,
      },
    },
  },
  thresholds: {
    reservation_spike_unexpected_rate: ["rate<0.01"],
  },
};

export function createSpikeReservation() {
  const seatIds = selectSeatIds();
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
        endpoint: "reservation-spike",
        mode,
      },
    }
  );

  spikeDuration.add(response.timings.duration, { case: caseName, mode });

  const isSuccess = response.status === 200;
  const isExpectedRejection = response.status >= 400 && response.status < 500
    && response.status !== 401
    && response.status !== 403;
  const isUnexpected = !isSuccess && !isExpectedRejection;

  if (isSuccess) {
    spikeSuccess.add(1, { case: caseName, mode });
  } else if (isExpectedRejection) {
    spikeRejected.add(1, { case: caseName, mode });
  } else {
    spikeUnexpected.add(1, { case: caseName, mode, status: String(response.status) });
  }

  spikeUnexpectedRate.add(isUnexpected, { case: caseName, mode });

  check(
    response,
    {
      "spike response is success or expected rejection": () => isSuccess || isExpectedRejection,
    },
    {
      case: caseName,
      endpoint: "reservation-spike",
      mode,
    }
  );
}

function selectSeatIds() {
  if (mode === "same") {
    return sameSeatIds;
  }

  if (mode === "overlap") {
    return overlapPairs[exec.scenario.iterationInTest % overlapPairs.length];
  }

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

function parseSeatIds(value) {
  return value
    .split(",")
    .map((seatId) => Number(seatId.trim()))
    .filter((seatId) => Number.isInteger(seatId) && seatId > 0);
}
