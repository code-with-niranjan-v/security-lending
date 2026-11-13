import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "fix-status",
  "name": "Read lender FIX adapter status",
  "service": "lenderAdapter",
  "method": "GET",
  "path": "/api/v1/adapter/status",
  "global": true
});
