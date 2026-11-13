import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-loans",
  "name": "List loans",
  "service": "lender",
  "method": "GET",
  "path": "/loans",
  "orgScoped": true,
  "paged": true
});
