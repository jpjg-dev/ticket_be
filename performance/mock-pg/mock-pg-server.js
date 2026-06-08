const http = require("node:http");

const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 18080;

function createMockPgServer() {
  const approvalByIdempotencyKey = new Map();

  return http.createServer(async (request, response) => {
    if (request.method === "GET" && request.url === "/health") {
      sendJson(response, 200, { status: "UP" });
      return;
    }

    if (request.method === "POST" && request.url === "/v1/payments/confirm") {
      const idempotencyKey = request.headers["idempotency-key"];
      if (typeof idempotencyKey !== "string" || idempotencyKey.length === 0) {
        sendJson(response, 400, { code: "MISSING_IDEMPOTENCY_KEY" });
        return;
      }

      const existingApproval = approvalByIdempotencyKey.get(idempotencyKey);
      if (existingApproval) {
        sendJson(response, 200, existingApproval);
        return;
      }

      const body = await readJsonBody(request);
      if (!isValidConfirmRequest(body)) {
        sendJson(response, 400, { code: "INVALID_CONFIRM_REQUEST" });
        return;
      }

      const approval = {
        paymentKey: body.paymentKey,
        orderId: body.orderId,
        totalAmount: body.amount,
        currency: "KRW",
        method: "CARD",
        status: "DONE",
      };

      approvalByIdempotencyKey.set(idempotencyKey, approval);
      sendJson(response, 200, approval);
      return;
    }

    sendJson(response, 404, { code: "NOT_FOUND" });
  });
}

function isValidConfirmRequest(body) {
  return body !== null
    && typeof body === "object"
    && typeof body.paymentKey === "string"
    && body.paymentKey.length > 0
    && typeof body.orderId === "string"
    && body.orderId.length > 0
    && Number.isInteger(body.amount)
    && body.amount > 0;
}

async function readJsonBody(request) {
  let rawBody = "";
  for await (const chunk of request) {
    rawBody += chunk;
  }

  try {
    return JSON.parse(rawBody);
  } catch {
    return null;
  }
}

function sendJson(response, statusCode, body) {
  response.writeHead(statusCode, { "Content-Type": "application/json" });
  response.end(JSON.stringify(body));
}

if (require.main === module) {
  const host = process.env.MOCK_PG_HOST || DEFAULT_HOST;
  const port = Number(process.env.MOCK_PG_PORT || DEFAULT_PORT);
  const server = createMockPgServer();

  server.listen(port, host, () => {
    console.log(`Mock PG listening on http://${host}:${port}`);
  });
}

module.exports = { createMockPgServer };

