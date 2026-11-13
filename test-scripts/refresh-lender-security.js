import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "refresh-lender-security",
  "name": "Refresh security price",
  "service": "lender",
  "method": "POST",
  "path": "/api/v1/securities/{id}/refresh",
  "idEnv": "SECURITY_ID"
});
