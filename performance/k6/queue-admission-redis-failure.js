import http from "k6/http";
import { check } from "k6";
import { SharedArray } from "k6/data";
import { Rate } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:18081";
const origin = __ENV.ORIGIN || "https://localhost:3000";
const scheduleId = Number(__ENV.SCHEDULE_ID || 18);
const caseName = __ENV.CASE_NAME || "queue-admission-redis-failure";

const perfUsers = new SharedArray("performance users", () =>
  JSON.parse(open("../data/perf-users.json"))
);

const unexpectedResponse = new Rate("queue_redis_failure_unexpected_response");

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    queue_redis_failure: {
      executor: "shared-iterations",
      vus: 1,
      iterations: Number(__ENV.ITERATIONS || 10),
      maxDuration: "30s",
      exec: "requestAdmissionWhileRedisIsDown",
      tags: { case: caseName },
    },
  },
  thresholds: {
    queue_redis_failure_unexpected_response: ["rate==0"],
    [`checks{case:${caseName}}`]: ["rate==1"],
  },
};

export function requestAdmissionWhileRedisIsDown() {
  const response = http.post(
    `${baseUrl}/api/v1/queue/admissions`,
    JSON.stringify({ scheduleId }),
    {
      headers: {
        "Content-Type": "application/json",
        Cookie: perfUsers[0].cookie,
        Origin: origin,
        Referer: `${origin}/`,
      },
      tags: { case: caseName, endpoint: "queue-admission" },
    }
  );

  const expected = response.status === 503 && response.headers["Retry-After"] === "1";
  unexpectedResponse.add(!expected);
  check(
    response,
    {
      "Redis outage rejects queue admission": () => response.status === 503,
      "503 includes Retry-After": () => response.headers["Retry-After"] === "1",
    },
    { case: caseName }
  );
}
