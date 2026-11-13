import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "approve-locate",
  "name": "Approve locate availability",
  "service": "lender",
  "method": "POST",
  "path": "/locates/{id}/approve",
  "orgScoped": true,
  "idEnv": "LOCATE_ID",
  "body": {
    "quantity": 100,
    "reason": "Availability verified"
  }
});
