import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "list-lender-securities",
  "name": "List lender securities",
  "service": "lender",
  "method": "GET",
  "path": "/api/v1/securities"
});
