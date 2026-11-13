import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-audit",
  "name": "Read audit events",
  "service": "lender",
  "method": "GET",
  "path": "/audit",
  "orgScoped": true,
  "paged": true
});
