import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "update-lender-user",
  "name": "Update lender user role and active state",
  "service": "lender",
  "method": "PATCH",
  "path": "/users/{id}",
  "orgScoped": true,
  "idEnv": "LENDER_USER_ID",
  "body": {
    "role": "ADMIN",
    "active": true
  }
});
