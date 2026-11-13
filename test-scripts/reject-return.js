import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "reject-return",
  "name": "Reject return",
  "service": "lender",
  "method": "POST",
  "path": "/returns/{id}/reject",
  "orgScoped": true,
  "idEnv": "RETURN_ID",
  "body": {
    "reason": "Transfer not received"
  }
});
