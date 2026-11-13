import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-loan-details",
  "name": "Read loan details",
  "service": "lender",
  "method": "GET",
  "path": "/loans/{id}",
  "orgScoped": true,
  "idEnv": "LOAN_ID"
});
