import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "confirm-margin-call",
  "name": "Confirm lender margin call",
  "service": "lender",
  "method": "POST",
  "path": "/margin-calls/{id}/confirm",
  "orgScoped": true,
  "idEnv": "MARGIN_CALL_ID",
  "body": {
    "reference": "DEMO-MARGIN-CALL-CONFIRM"
  }
});
