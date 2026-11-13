import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "add-lender-inventory",
  "name": "Add lender inventory",
  "service": "lender",
  "method": "POST",
  "path": "/inventory",
  "orgScoped": true,
  "body": {
    "securityId": 1,
    "quantity": 10000,
    "reference": "DEMO-CUSTODY-INITIAL"
  }
});
