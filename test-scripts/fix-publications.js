import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "fix-publications",
  "name": "Read lender FIX publications",
  "service": "lenderAdapter",
  "method": "GET",
  "path": "/api/v1/adapter/publications",
  "paged": true,
  "global": true
});
