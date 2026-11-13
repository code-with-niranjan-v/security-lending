import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-fees",
  "name": "List fee bills",
  "service": "lender",
  "method": "GET",
  "path": "/fees",
  "orgScoped": true,
  "paged": true
});
