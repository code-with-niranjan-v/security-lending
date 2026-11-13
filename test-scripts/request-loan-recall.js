import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "request-loan-recall",
  "name": "Request loan recall",
  "service": "lender",
  "method": "POST",
  "path": "/loans/{id}/recall-requests",
  "orgScoped": true,
  "idEnv": "LOAN_ID",
  "body": {
    "quantity": 50,
    "reason": "Portfolio sale",
    "clientRequestId": "DEMO-RECALL-$RUN_ID"
  }
});
