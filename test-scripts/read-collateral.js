import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-collateral",
  "name": "List collateral",
  "service": "lender",
  "method": "GET",
  "path": "/collateral",
  "orgScoped": true,
  "paged": true
});
