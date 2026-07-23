import http from "k6/http";
import { check } from "k6";
import { SharedArray } from "k6/data";
import exec from "k6/execution";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:18080";
const origin = __ENV.ORIGIN || "https://localhost:3000";
const scheduleId = Number(__ENV.SCHEDULE_ID || 18);
const queueUsers = Number(__ENV.QUEUE_USERS || 1000);
const vus = Number(__ENV.VUS || Math.min(queueUsers, 200));
const caseName = __ENV.CASE_NAME || `queue-admission-${queueUsers}`;

const perfUsers = new SharedArray("performance users", () =>
  JSON.parse(open("../data/perf-users.json"))
);

const registrationDuration = new Trend("queue_registration_duration", true);
const registrationFailed = new Rate("queue_registration_failed");

if (!Number.isInteger(scheduleId) || scheduleId <= 0) {
  throw new Error("SCHEDULE_ID must be a positive integer.");
}

if (!Number.isInteger(queueUsers) || queueUsers <= 0 || queueUsers > perfUsers.length) {
  throw new Error(`QUEUE_USERS must be between 1 and ${perfUsers.length}.`);
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    queue_registration: {
      executor: "shared-iterations",
      vus,
      iterations: queueUsers,
      maxDuration: __ENV.MAX_DURATION || "5m",
      exec: "registerQueue",
      tags: {
        case: caseName,
        endpoint: "queue-admission",
      },
    },
  },
  thresholds: {
    queue_registration_failed: ["rate<0.01"],
    [`checks{case:${caseName}}`]: ["rate>0.99"],
  },
};

export function registerQueue() {
  const user = perfUsers[exec.scenario.iterationInTest];
  const response = http.post(
    `${baseUrl}/api/v1/queue/admissions`,
    JSON.stringify({ scheduleId }),
    {
      headers: {
        "Content-Type": "application/json",
        Cookie: user.cookie,
        Origin: origin,
        Referer: `${origin}/`,
      },
      tags: {
        case: caseName,
        endpoint: "queue-admission",
      },
    }
  );

  registrationDuration.add(response.timings.duration, { case: caseName });
  registrationFailed.add(response.status !== 200, { case: caseName });

  let body = null;
  if (response.status === 200) {
    body = response.json();
  }

  check(
    response,
    {
      "queue admission returns 200": (result) => result.status === 200,
      "queue status is valid": () =>
        body !== null && ["WAITING", "ADMITTED", "BYPASSED"].includes(body.status),
      "enforced queue returns token": () => body?.status === "BYPASSED" || Boolean(body?.queueToken),
    },
    {
      case: caseName,
      endpoint: "queue-admission",
    }
  );
}
