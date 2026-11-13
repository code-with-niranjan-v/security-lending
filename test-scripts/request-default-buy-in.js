import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "request-default-buy-in",
  "name": "Request default buy-in",
  "service": "lender",
  "method": "POST",
  "path": "/defaults/{id}/buy-in-requests",
  "orgScoped": true,
  "idEnv": "DEFAULT_ID",
  "body": {
    "clientRequestId": "DEMO-BUYIN-$RUN_ID"
  }
});
