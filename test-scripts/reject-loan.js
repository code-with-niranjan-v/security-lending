import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "reject-loan",
  "name": "Reject loan",
  "service": "lender",
  "method": "POST",
  "path": "/loans/{id}/reject",
  "orgScoped": true,
  "idEnv": "LOAN_ID",
  "body": {
    "reason": "Risk review declined"
  }
});
