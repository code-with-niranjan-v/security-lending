import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-ledger",
  "name": "Read lender ledger",
  "service": "lender",
  "method": "GET",
  "path": "/ledger",
  "orgScoped": true,
  "paged": true
});
