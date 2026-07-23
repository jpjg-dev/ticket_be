import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const eventId = __ENV.EVENT_ID || "1";
const caseName = __ENV.CASE_NAME || "event-cache-read-baseline";
const vus = Number(__ENV.VUS || 20);
const duration = __ENV.DURATION || "30s";

const eventListDuration = new Trend("event_cache_list_duration", true);
const eventDetailDuration = new Trend("event_cache_detail_duration", true);
const eventCacheUnexpectedRate = new Rate("event_cache_unexpected_rate");

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    event_cache_read: {
      executor: "constant-vus",
      vus,
      duration,
      exec: "queryEventCacheTargets",
      tags: {
        case: caseName,
        endpoint: "event-cache-read",
      },
    },
  },
  thresholds: {
    event_cache_unexpected_rate: ["rate<0.01"],
    "checks{endpoint:event-cache-read}": ["rate>0.99"],
  },
};

export function queryEventCacheTargets() {
  const listResponse = http.get(`${baseUrl}/api/v1/event`, {
    tags: {
      case: caseName,
      endpoint: "event-list",
    },
  });

  eventListDuration.add(listResponse.timings.duration, { case: caseName });
  eventCacheUnexpectedRate.add(listResponse.status !== 200, { case: caseName, endpoint: "event-list" });

  check(
    listResponse,
    {
      "event list returns 200": (response) => response.status === 200,
    },
    {
      case: caseName,
      endpoint: "event-cache-read",
    }
  );

  const detailResponse = http.get(`${baseUrl}/api/v1/event/${eventId}`, {
    tags: {
      case: caseName,
      endpoint: "event-detail",
    },
  });

  eventDetailDuration.add(detailResponse.timings.duration, { case: caseName });
  eventCacheUnexpectedRate.add(detailResponse.status !== 200, { case: caseName, endpoint: "event-detail" });

  check(
    detailResponse,
    {
      "event detail returns 200": (response) => response.status === 200,
    },
    {
      case: caseName,
      endpoint: "event-cache-read",
    }
  );
}
