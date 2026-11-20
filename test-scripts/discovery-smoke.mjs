import assert from "node:assert/strict";
const gateway = process.env.DISCOVERY_GATEWAY_URL || "http://localhost:8090";
const replicas = (
  process.env.DISCOVERY_ACCOUNTS_REPLICAS ||
  "http://localhost:8086,http://localhost:18086"
).split(",");
async function call(base, path, options = {}) {
  return fetch(base + path, { ...options, signal: AbortSignal.timeout(10000) });
}
const instances = new Set();
for (let attempt = 0; attempt < 16; attempt++) {
  const response = await call(gateway, "/accounts/api/v1/auth/me");
  assert.equal(
    response.status,
    401,
    "Anonymous session endpoint must be reachable and require login",
  );
  instances.add(response.headers.get("x-service-instance"));
}
assert.equal(
  instances.size,
  replicas.length,
  `Expected account routing across ${replicas.length} replica(s), saw ${[...instances]}`,
);
assert(!instances.has(null), "Backend must supply X-Service-Instance");
for (const path of [
  "/accounts/internal/lendora/borrowers",
  "/lender/internal/lendora/provision",
  "/borrower/actuator/env",
  "/actuator/metrics",
]) {
  assert.equal(
    (await call(gateway, path)).status,
    404,
    `Internal endpoint exposed: ${path}`,
  );
}
console.log(
  `Routing and edge restrictions passed: ${[...instances].join(", ")}`,
);
const preflight = await call(gateway, "/accounts/api/v1/auth/login", {
  method: "OPTIONS",
  headers: {
    Origin: "http://localhost:5173",
    "Access-Control-Request-Method": "POST",
    "Access-Control-Request-Headers": "content-type",
  },
});
assert.equal(preflight.status, 200);
assert.equal(
  preflight.headers.get("access-control-allow-origin"),
  "http://localhost:5173",
);
assert.equal(preflight.headers.get("access-control-allow-credentials"), "true");
const email = process.env.DISCOVERY_TEST_EMAIL;
const password = process.env.DISCOVERY_TEST_PASSWORD;
if (email && password) {
  const login = await call(gateway, "/accounts/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, rememberMe: false }),
  });
  assert.equal(login.status, 200, "Login failed");
  const cookie = login.headers.get("set-cookie")?.split(";")[0];
  assert(cookie?.startsWith("LENDORA_SESSION="), "Session cookie missing");
  const headers = { Cookie: cookie };
  const identity = (
    await (await call(gateway, "/accounts/api/v1/auth/me", { headers })).json()
  ).data;
  for (const replica of replicas) {
    const response = await call(replica, "/api/v1/auth/me", { headers });
    assert.equal(response.status, 200, "Session must work on every replica");
    assert.equal((await response.json()).data.user.email, identity.user.email);
  }
  const prefix =
    identity.organization.type === "BORROWER" ? "borrower" : "lender";
  const org = identity.organization.platformOrganizationId;
  for (const route of [
    `/${prefix}/api/v1/organizations/${org}`,
    `/${prefix}/api/v1/organizations/${org}/loans`,
  ]) {
    assert.equal(
      (await call(gateway, route, { headers })).status,
      200,
      "Authorized workspace read failed",
    );
  }
  assert.equal(
    (
      await call(
        gateway,
        `/${prefix}/api/v1/organizations/${Number(org) + 1000000}`,
        { headers },
      )
    ).status,
    403,
    "Organization isolation failed",
  );
  const loadRequests = Number(process.env.DISCOVERY_LOAD_REQUESTS || 0);
  assert(
    Number.isInteger(loadRequests) &&
      loadRequests >= 0 &&
      loadRequests <= 10000,
  );
  if (loadRequests) {
    let next = 0;
    const latency = [];
    const backends = new Set();
    const started = performance.now();
    await Promise.all(
      Array.from({ length: Math.min(8, loadRequests) }, async () => {
        while (next++ < loadRequests) {
          const before = performance.now();
          const result = await call(
            gateway,
            `/${prefix}/api/v1/organizations/${org}/loans`,
            { headers },
          );
          await result.arrayBuffer();
          assert.equal(result.status, 200, "Load read failed");
          latency.push(performance.now() - before);
          backends.add(result.headers.get("x-service-instance"));
        }
      }),
    );
    latency.sort((a, b) => a - b);
    console.log(
      JSON.stringify({
        requests: latency.length,
        errors: 0,
        elapsedMs: Math.round(performance.now() - started),
        p50Ms: Math.round(latency[Math.floor(latency.length * 0.5)]),
        p95Ms: Math.round(
          latency[
            Math.min(latency.length - 1, Math.floor(latency.length * 0.95))
          ],
        ),
        instances: [...backends],
      }),
    );
  }
  const logout = await call(replicas[1] || replicas[0], "/api/v1/auth/logout", {
    method: "POST",
    headers,
  });
  assert.equal(logout.status, 200);
  for (const replica of replicas)
    assert.equal(
      (await call(replica, "/api/v1/auth/me", { headers })).status,
      401,
      "Logout must invalidate the shared session",
    );
  console.log(
    "Shared login, workspace authorization, organization isolation and cross-replica logout passed.",
  );
} else
  console.log(
    "Authenticated checks skipped; set DISCOVERY_TEST_EMAIL and DISCOVERY_TEST_PASSWORD to enable them.",
  );
