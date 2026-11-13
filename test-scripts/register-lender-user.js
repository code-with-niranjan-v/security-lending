import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "register-lender-user",
  "name": "Register lender user",
  "service": "lender",
  "method": "POST",
  "path": "/users",
  "orgScoped": true,
  "body": {
    "name": "Demo Admin",
    "email": "admin@lender.example",
    "role": "ADMIN"
  }
});
