import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-defaults",
  "name": "List defaults",
  "service": "lender",
  "method": "GET",
  "path": "/defaults",
  "orgScoped": true,
  "paged": true
});
