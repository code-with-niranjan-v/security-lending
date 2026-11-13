import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "settle-return",
  "name": "Record return settlement",
  "service": "lender",
  "method": "POST",
  "path": "/returns/{id}/settle",
  "orgScoped": true,
  "idEnv": "RETURN_ID",
  "body": {
    "reference": "DEMO-RETURN-SETTLEMENT"
  }
});
