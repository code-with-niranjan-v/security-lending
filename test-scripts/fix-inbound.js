import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "fix-inbound",
  "name": "Read lender FIX inbound messages",
  "service": "lenderAdapter",
  "method": "GET",
  "path": "/api/v1/adapter/inbound",
  "paged": true,
  "global": true
});
