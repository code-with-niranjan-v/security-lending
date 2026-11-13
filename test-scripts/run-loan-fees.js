import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "run-loan-fees",
  "name": "Run due lending fees for a loan",
  "service": "lifecycle",
  "method": "POST",
  "path": "/api/v1/lifecycle/loans/{id}/fees/run",
  "idEnv": "LOAN_ID"
});
