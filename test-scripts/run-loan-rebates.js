import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "run-loan-rebates",
  "name": "Run due cash rebates for a loan",
  "service": "lifecycle",
  "method": "POST",
  "path": "/api/v1/lifecycle/loans/{id}/rebates/run",
  "idEnv": "LOAN_ID"
});
