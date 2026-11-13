import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "import-lender-securities",
  "name": "Import market security references",
  "service": "lender",
  "method": "POST",
  "path": "/api/v1/securities/import"
});
