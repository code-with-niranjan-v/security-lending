import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "settle-loan",
  "name": "Record securities settlement",
  "service": "lender",
  "method": "POST",
  "path": "/loans/{id}/settle",
  "orgScoped": true,
  "idEnv": "LOAN_ID",
  "body": {
    "reference": "DEMO-SEC-SETTLEMENT"
  }
});
