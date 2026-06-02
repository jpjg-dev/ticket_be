import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "https://localhost:8080";
const userId = __ENV.USER_ID;
const caseName = __ENV.CASE_NAME || "mypage-baseline";
const dataSet = __ENV.DATA_SET || "unknown";
const vus = Number(__ENV.VUS || 1);
const duration = __ENV.DURATION || "10s";
const cookie = __ENV.COOKIE;

const mypageDuration = new Trend("mypage_duration", true);
const mypageFailed = new Rate("mypage_failed");
const mypageEmptyReservations = new Rate("mypage_empty_reservations");
const mypageEmptyPayments = new Rate("mypage_empty_payments");

if (!userId) {
  throw new Error("USER_ID is required. Example: USER_ID=1 COOKIE=\"JSESSIONID=...; access_token=...\" k6 run performance/k6/mypage-baseline.js");
}

if (!cookie) {
  throw new Error("COOKIE is required because /api/v1/users/{userId} needs authentication.");
}

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS_VERIFY !== "false",
  scenarios: {
    mypage_baseline: {
      executor: "constant-vus",
      vus,
      duration,
      exec: "queryMyPage",
      tags: {
        case: caseName,
        data_set: dataSet,
        endpoint: "mypage",
      },
    },
  },
  thresholds: {
    "checks{endpoint:mypage}": ["rate>0.99"],
    mypage_failed: ["rate<0.01"],
  },
};

export function queryMyPage() {
  const response = http.get(`${baseUrl}/api/v1/users/${userId}`, {
    headers: {
      Cookie: cookie,
    },
    tags: {
      case: caseName,
      data_set: dataSet,
      endpoint: "mypage",
    },
  });

  mypageDuration.add(response.timings.duration, { case: caseName, data_set: dataSet });
  mypageFailed.add(response.status !== 200, { case: caseName, data_set: dataSet });

  let body = null;
  if (response.status === 200) {
    body = response.json();
    mypageEmptyReservations.add(!body.reservations || body.reservations.length === 0, {
      case: caseName,
      data_set: dataSet,
    });
    mypageEmptyPayments.add(!body.payments || body.payments.length === 0, {
      case: caseName,
      data_set: dataSet,
    });
  }

  check(response, {
    "mypage returns 200": (result) => result.status === 200,
    "mypage has reservations": () => body !== null && Array.isArray(body.reservations),
    "mypage has payments": () => body !== null && Array.isArray(body.payments),
  }, {
    case: caseName,
    data_set: dataSet,
    endpoint: "mypage",
  });
}
