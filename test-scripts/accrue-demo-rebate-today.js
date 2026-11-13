import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "accrue-demo-rebate-today",
  "name": "Record today's demo rebate accrual",
  "service": "lifecycle",
  "method": "POST",
  "path": "/api/v1/lifecycle/loans/{id}/rebates/demo/accrue-today",
  "idEnv": "LOAN_ID"
});
