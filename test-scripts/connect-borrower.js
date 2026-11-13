import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "connect-borrower",
  "name": "Connect borrower institution",
  "service": "lender",
  "method": "POST",
  "path": "/borrowers",
  "orgScoped": true,
  "body": {
    "externalId": "BORROWER_DEMO",
    "borrowerOrganizationId": 1,
    "name": "Demo Borrower"
  }
});
