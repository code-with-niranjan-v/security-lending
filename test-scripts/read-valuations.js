import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-valuations",
  "name": "Read loan valuations",
  "service": "lender",
  "method": "GET",
  "path": "/valuations",
  "orgScoped": true,
  "paged": true
});
