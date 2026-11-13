import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-returns",
  "name": "List returns",
  "service": "lender",
  "method": "GET",
  "path": "/returns",
  "orgScoped": true,
  "paged": true
});
