import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "list-lender-organizations",
  "name": "List organizations",
  "service": "lender",
  "method": "GET",
  "path": "/api/v1/organizations"
});
