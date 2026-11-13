import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "request-rebate-payment",
  "name": "Request cash-collateral rebate payment",
  "service": "lender",
  "method": "POST",
  "path": "/rebates/{id}/payment-requests",
  "orgScoped": true,
  "idEnv": "REBATE_ID",
  "body": {
    "clientRequestId": "DEMO-REBATE-$RUN_ID"
  }
});
