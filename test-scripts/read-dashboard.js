import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-dashboard",
  "name": "Read lender dashboard",
  "service": "lender",
  "method": "GET",
  "path": "/dashboard",
  "orgScoped": true,
  "paged": false
});
