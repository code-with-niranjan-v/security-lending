import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "reject-collateral",
  "name": "Reject collateral receipt",
  "service": "lender",
  "method": "POST",
  "path": "/collateral/{id}/reject",
  "orgScoped": true,
  "idEnv": "COLLATERAL_ID",
  "body": {
    "reason": "Collateral not received"
  }
});
