import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "adjust-lender-inventory",
  "name": "Adjust inventory",
  "service": "lender",
  "method": "POST",
  "path": "/inventory/{id}/adjustments",
  "orgScoped": true,
  "idEnv": "INVENTORY_ID",
  "body": {
    "quantityDelta": 100,
    "reference": "DEMO-CUSTODY-ADJUSTMENT"
  }
});
