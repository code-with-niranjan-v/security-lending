import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-margin-calls",
  "name": "List margin calls",
  "service": "lender",
  "method": "GET",
  "path": "/margin-calls",
  "orgScoped": true,
  "paged": true
});
