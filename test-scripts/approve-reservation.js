import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "approve-reservation",
  "name": "Approve reservation",
  "service": "lender",
  "method": "POST",
  "path": "/reservations/{id}/approve",
  "orgScoped": true,
  "idEnv": "RESERVATION_ID",
  "body": {
    "quantity": 100,
    "reason": "Reserve inventory"
  }
});
