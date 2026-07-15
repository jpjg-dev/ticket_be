import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://localhost:18080";
const eventId = __ENV.EVENT_ID || "1";
const caseName = __ENV.CASE_NAME || "event-cache-redis-failure";
const vus = Number(__ENV.VUS || 50);
const duration = __ENV.DURATION || "30s";
const expectedRetryAfter = __ENV.EXPECTED_RETRY_AFTER || "1";

const fallbackSuccessRate = new Rate("redis_failure_fallback_success_rate");
const rejectedRate = new Rate("redis_failure_rejected_rate");
const unexpectedRate = new Rate("redis_failure_unexpected_rate");
const retryAfterMissingRate = new Rate("redis_failure_retry_after_missing_rate");
const responseDuration = new Trend("redis_failure_response_duration", true);

export const options = {
  scenarios: {
    redis_failure_reads: {
      executor: "constant-vus",
      vus,
      duration,
      exec: "queryEventCacheTargets",
      tags: {
        case: caseName,
      },
    },
  },
  thresholds: {
    redis_failure_unexpected_rate: ["rate==0"],
    redis_failure_retry_after_missing_rate: ["rate==0"],
    [`checks{case:${caseName}}`]: ["rate>0.99"],
  },
};

export function queryEventCacheTargets() {
  record(http.get(`${baseUrl}/api/v1/event`), "event-list");
  record(http.get(`${baseUrl}/api/v1/event/${eventId}`), "event-detail");
}

function record(response, endpoint) {
  const fallbackSucceeded = response.status === 200;
  const rejected = response.status === 503;
  const retryAfterMissing = rejected && response.headers["Retry-After"] !== expectedRetryAfter;

  fallbackSuccessRate.add(fallbackSucceeded, { endpoint });
  rejectedRate.add(rejected, { endpoint });
  unexpectedRate.add(!fallbackSucceeded && !rejected, { endpoint });
  retryAfterMissingRate.add(retryAfterMissing, { endpoint });
  responseDuration.add(response.timings.duration, { endpoint });

  check(
    response,
    {
      "returns fallback success or protected rejection": () => fallbackSucceeded || rejected,
      "503 includes Retry-After": () => !rejected || !retryAfterMissing,
    },
    {
      case: caseName,
      endpoint,
    }
  );
}
