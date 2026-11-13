import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "reject-agreement",
  "name": "Reject MSLA",
  "service": "lender",
  "method": "POST",
  "path": "/agreements/{id}/reject",
  "orgScoped": true,
  "idEnv": "AGREEMENT_ID",
  "body": {
    "reason": "Demo rejection"
  }
});
