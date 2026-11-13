import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "confirm-collateral",
  "name": "Confirm collateral receipt",
  "service": "lender",
  "method": "POST",
  "path": "/collateral/{id}/confirm",
  "orgScoped": true,
  "idEnv": "COLLATERAL_ID",
  "body": {
    "reference": "DEMO-COLLATERAL-RECEIPT"
  }
});
