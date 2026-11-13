import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "list-lender-roles",
  "name": "List organization roles",
  "service": "lender",
  "method": "GET",
  "path": "/api/v1/organizations/roles"
});
