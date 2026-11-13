import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "reject-collateral-release",
  "name": "Reject collateral release",
  "service": "lender",
  "method": "POST",
  "path": "/collateral/{id}/release-rejections",
  "orgScoped": true,
  "idEnv": "COLLATERAL_ID",
  "body": {
    "reason": "Coverage would be insufficient"
  }
});
