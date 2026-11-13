import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "mark-lender-notification-read",
  "name": "Mark lender notification as read",
  "service": "lender",
  "method": "POST",
  "path": "/notifications/{id}/read",
  "orgScoped": true,
  "idEnv": "NOTIFICATION_ID"
});
