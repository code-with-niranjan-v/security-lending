import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-rebate-payments",
  "name": "List rebate payments",
  "service": "lender",
  "method": "GET",
  "path": "/rebate-payments",
  "orgScoped": true,
  "paged": true
});
