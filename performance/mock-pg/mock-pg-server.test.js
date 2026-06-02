const assert = require("node:assert/strict");
const { after, before, test } = require("node:test");

const { createMockPgServer } = require("./mock-pg-server");

let server;
let baseUrl;

before(async () => {
  server = createMockPgServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  baseUrl = `http://127.0.0.1:${address.port}`;
});

after(async () => {
  await new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
});

test("GET /health returns Mock PG health status", async () => {
  const response = await fetch(`${baseUrl}/health`);

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "UP" });
});

test("POST /v1/payments/confirm returns Toss-compatible approval response", async () => {
  const request = {
    paymentKey: "perf-payment-key-1",
    orderId: "order-id-1",
    amount: 2200,
  };

  const response = await confirm(request, "confirm:order-id-1");

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    paymentKey: "perf-payment-key-1",
    orderId: "order-id-1",
    totalAmount: 2200,
    currency: "KRW",
    method: "CARD",
    status: "DONE",
  });
});

test("POST /v1/payments/confirm rejects an invalid request body", async () => {
  const response = await confirm(
    { paymentKey: "", orderId: "order-id-2", amount: 2200 },
    "confirm:order-id-2"
  );

  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { code: "INVALID_CONFIRM_REQUEST" });
});

test("POST /v1/payments/confirm replays the first response for the same idempotency key", async () => {
  const idempotencyKey = "confirm:order-id-3";
  const first = await confirm(
    { paymentKey: "perf-payment-key-3", orderId: "order-id-3", amount: 3300 },
    idempotencyKey
  );
  const replay = await confirm(
    { paymentKey: "different-key", orderId: "different-order", amount: 9999 },
    idempotencyKey
  );

  assert.equal(first.status, 200);
  assert.equal(replay.status, 200);
  assert.deepEqual(await replay.json(), await first.json());
});

function confirm(body, idempotencyKey) {
  return fetch(`${baseUrl}/v1/payments/confirm`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(body),
  });
}

