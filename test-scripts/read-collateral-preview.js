import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-collateral-preview",
  "name": "Read indicative collateral preview",
  "service": "lender",
  "method": "GET",
  "path": "/loans/{id}/collateral-preview",
  "orgScoped": true,
  "idEnv": "LOAN_ID"
});
