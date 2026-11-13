import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "reject-reservation",
  "name": "Reject reservation",
  "service": "lender",
  "method": "POST",
  "path": "/reservations/{id}/reject",
  "orgScoped": true,
  "idEnv": "RESERVATION_ID",
  "body": {
    "reason": "Inventory unavailable"
  }
});
