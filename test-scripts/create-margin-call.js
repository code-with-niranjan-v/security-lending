import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "create-margin-call",
  "name": "Trigger manual margin call",
  "service": "lifecycle",
  "method": "POST",
  "path": "/api/v1/lifecycle/loans/{id}/margin-calls",
  "idEnv": "LOAN_ID",
  "body": {
    "amount": 250
  }
});
