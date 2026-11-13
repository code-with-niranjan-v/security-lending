import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-collateral-history",
  "name": "Read collateral history",
  "service": "lender",
  "method": "GET",
  "path": "/collateral-history",
  "orgScoped": true,
  "paged": true
});
