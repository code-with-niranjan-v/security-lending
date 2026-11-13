import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "approve-agreement",
  "name": "Approve MSLA",
  "service": "lender",
  "method": "POST",
  "path": "/agreements/{id}/approve",
  "orgScoped": true,
  "idEnv": "AGREEMENT_ID"
});
