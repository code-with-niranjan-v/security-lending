import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "fix-retry-publications",
  "name": "Retry lender FIX publications",
  "service": "lenderAdapter",
  "method": "POST",
  "path": "/api/v1/adapter/publications/{id}/retry",
  "idEnv": "FIX_MESSAGE_ID",
  "global": true
});
