import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "fix-outbound",
  "name": "Read lender FIX outbound messages",
  "service": "lenderAdapter",
  "method": "GET",
  "path": "/api/v1/adapter/outbound",
  "paged": true,
  "global": true
});
