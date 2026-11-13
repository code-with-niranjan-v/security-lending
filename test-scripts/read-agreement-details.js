import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-agreement-details",
  "name": "Read agreement details",
  "service": "lender",
  "method": "GET",
  "path": "/agreements/{id}",
  "orgScoped": true,
  "idEnv": "AGREEMENT_ID"
});
