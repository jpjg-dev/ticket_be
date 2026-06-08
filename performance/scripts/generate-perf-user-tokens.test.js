const assert = require("node:assert/strict");
const { test } = require("node:test");

const { createAccessToken, createPerfUsers } = require("./generate-perf-user-tokens");

const secret = "abcdefghijklmnopqrstuvwxyz123456";

test("createAccessToken creates an HS256 ACCESS token for the requested user", () => {
  const issuedAtSeconds = 1_780_000_000;
  const token = createAccessToken(101, secret, issuedAtSeconds, 3600);
  const [header, payload, signature] = token.split(".");

  assert.deepEqual(decodeBase64UrlJson(header), { alg: "HS256" });
  assert.deepEqual(decodeBase64UrlJson(payload), {
    sub: "101",
    type: "ACCESS",
    iat: issuedAtSeconds,
    exp: issuedAtSeconds + 3600,
  });
  assert.ok(signature.length > 0);
});

test("createPerfUsers creates access-token cookies for each user id", () => {
  const users = createPerfUsers([101, 102], secret, 1_780_000_000, 3600);

  assert.equal(users.length, 2);
  assert.equal(users[0].userId, 101);
  assert.match(users[0].cookie, /^__Host-access_token=.+/);
  assert.equal(users[1].userId, 102);
});

function decodeBase64UrlJson(value) {
  return JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
}

