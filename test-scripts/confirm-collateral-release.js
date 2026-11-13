import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "confirm-collateral-release",
  "name": "Confirm collateral release",
  "service": "lender",
  "method": "POST",
  "path": "/collateral/{id}/release-confirmations",
  "orgScoped": true,
  "idEnv": "COLLATERAL_ID",
  "body": {
    "reference": "DEMO-COLLATERAL-RELEASE"
  }
});
