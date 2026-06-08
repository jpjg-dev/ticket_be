const crypto = require("node:crypto");
const fs = require("node:fs");

const ACCESS_TOKEN_COOKIE_NAME = "__Host-access_token";
const DEFAULT_EXPIRATION_SECONDS = 3600;

function createAccessToken(userId, secret, issuedAtSeconds, expirationSeconds) {
  const header = encodeBase64UrlJson({ alg: "HS256" });
  const payload = encodeBase64UrlJson({
    sub: String(userId),
    type: "ACCESS",
    iat: issuedAtSeconds,
    exp: issuedAtSeconds + expirationSeconds,
  });
  const signature = crypto
    .createHmac("sha256", secret)
    .update(`${header}.${payload}`)
    .digest("base64url");

  return `${header}.${payload}.${signature}`;
}

function createPerfUsers(userIds, secret, issuedAtSeconds, expirationSeconds) {
  return userIds.map((userId) => ({
    userId,
    cookie: `${ACCESS_TOKEN_COOKIE_NAME}=${createAccessToken(
      userId,
      secret,
      issuedAtSeconds,
      expirationSeconds
    )}`,
  }));
}

function readUserIdsFromStdin() {
  return fs
    .readFileSync(0, "utf8")
    .split(/\r?\n/)
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isInteger(value) && value > 0);
}

function encodeBase64UrlJson(value) {
  return Buffer.from(JSON.stringify(value), "utf8").toString("base64url");
}

if (require.main === module) {
  const secret = process.env.JWT_SECRET;
  if (!secret) {
    throw new Error("JWT_SECRET is required.");
  }

  const expirationSeconds = Number(
    process.env.PERF_ACCESS_TOKEN_EXPIRATION_SECONDS || DEFAULT_EXPIRATION_SECONDS
  );
  if (!Number.isInteger(expirationSeconds) || expirationSeconds <= 0) {
    throw new Error("PERF_ACCESS_TOKEN_EXPIRATION_SECONDS must be a positive integer.");
  }

  const userIds = readUserIdsFromStdin();
  if (userIds.length === 0) {
    throw new Error("At least one user id is required through stdin.");
  }

  const issuedAtSeconds = Math.floor(Date.now() / 1000);
  process.stdout.write(JSON.stringify(
    createPerfUsers(userIds, secret, issuedAtSeconds, expirationSeconds),
    null,
    2
  ));
}

module.exports = { createAccessToken, createPerfUsers };

