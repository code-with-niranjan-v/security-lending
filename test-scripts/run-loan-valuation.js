import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "run-loan-valuation",
  "name": "Run loan valuation",
  "service": "lifecycle",
  "method": "POST",
  "path": "/api/v1/lifecycle/loans/{id}/valuation/run",
  "idEnv": "LOAN_ID"
});
