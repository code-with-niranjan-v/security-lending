import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-account",
  "name": "Read lender account",
  "service": "lender",
  "method": "GET",
  "path": "/account",
  "orgScoped": true,
  "paged": false
});
