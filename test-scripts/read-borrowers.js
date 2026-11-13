import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-borrowers",
  "name": "List connected borrowers",
  "service": "lender",
  "method": "GET",
  "path": "/borrowers",
  "orgScoped": true,
  "paged": true
});
