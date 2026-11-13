import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "reject-locate",
  "name": "Reject locate",
  "service": "lender",
  "method": "POST",
  "path": "/locates/{id}/reject",
  "orgScoped": true,
  "idEnv": "LOCATE_ID",
  "body": {
    "reason": "Insufficient availability"
  }
});
