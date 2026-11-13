import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "register-lender-organization",
  "name": "Register lender organization",
  "service": "lender",
  "method": "POST",
  "path": "/api/v1/organizations",
  "body": {
    "name": "Demo Mutual Fund",
    "externalId": "LENDER_DEMO",
    "publicReferenceId": 1000001
  }
});
