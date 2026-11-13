import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-inventory-history",
  "name": "Read inventory history",
  "service": "lender",
  "method": "GET",
  "path": "/inventory-history",
  "orgScoped": true,
  "paged": true
});
