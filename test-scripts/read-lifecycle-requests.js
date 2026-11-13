import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-lifecycle-requests",
  "name": "Read lifecycle requests",
  "service": "lender",
  "method": "GET",
  "path": "/lifecycle-requests",
  "orgScoped": true,
  "paged": true
});
