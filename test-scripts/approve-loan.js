import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "approve-loan",
  "name": "Approve loan",
  "service": "lender",
  "method": "POST",
  "path": "/loans/{id}/approve",
  "orgScoped": true,
  "idEnv": "LOAN_ID"
});
