import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "list-lender-users",
  "name": "List lender users",
  "service": "lender",
  "method": "GET",
  "path": "/users",
  "orgScoped": true
});
