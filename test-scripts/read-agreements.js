import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-agreements",
  "name": "List agreements",
  "service": "lender",
  "method": "GET",
  "path": "/agreements",
  "orgScoped": true,
  "paged": true
});
