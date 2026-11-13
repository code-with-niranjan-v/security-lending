import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-notifications",
  "name": "List notifications",
  "service": "lender",
  "method": "GET",
  "path": "/notifications",
  "orgScoped": true,
  "paged": true
});
