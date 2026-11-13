import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-recalls",
  "name": "List recalls",
  "service": "lender",
  "method": "GET",
  "path": "/recalls",
  "orgScoped": true,
  "paged": true
});
