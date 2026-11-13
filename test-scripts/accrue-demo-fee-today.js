import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "accrue-demo-fee-today",
  "name": "Record today's demo lending fee accrual",
  "service": "lifecycle",
  "method": "POST",
  "path": "/api/v1/lifecycle/loans/{id}/fees/demo/accrue-today",
  "idEnv": "LOAN_ID"
});
