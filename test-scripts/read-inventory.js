import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-inventory",
  "name": "List lender inventory",
  "service": "lender",
  "method": "GET",
  "path": "/inventory",
  "orgScoped": true,
  "paged": true
});
