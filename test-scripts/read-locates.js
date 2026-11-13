import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-locates",
  "name": "List locate requests",
  "service": "lender",
  "method": "GET",
  "path": "/locates",
  "orgScoped": true,
  "paged": true
});
