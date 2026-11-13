import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "fix-retry-outbound",
  "name": "Retry lender FIX outbound",
  "service": "lenderAdapter",
  "method": "POST",
  "path": "/api/v1/adapter/outbound/{id}/retry",
  "idEnv": "FIX_MESSAGE_ID",
  "global": true
});
