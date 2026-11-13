import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-buy-ins",
  "name": "List buy-in requests",
  "service": "lender",
  "method": "GET",
  "path": "/buy-ins",
  "orgScoped": true,
  "paged": true
});
