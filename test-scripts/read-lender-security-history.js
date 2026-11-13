import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-lender-security-history",
  "name": "Read security OHLC history",
  "service": "lender",
  "method": "GET",
  "path": "/api/v1/securities/{id}/history",
  "idEnv": "SECURITY_ID"
});
