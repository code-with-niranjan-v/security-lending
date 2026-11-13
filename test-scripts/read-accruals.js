import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-accruals",
  "name": "Read fee and rebate accruals",
  "service": "lender",
  "method": "GET",
  "path": "/accruals",
  "orgScoped": true,
  "paged": true
});
