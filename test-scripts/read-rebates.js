import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-rebates",
  "name": "List rebates",
  "service": "lender",
  "method": "GET",
  "path": "/rebates",
  "orgScoped": true,
  "paged": true
});
