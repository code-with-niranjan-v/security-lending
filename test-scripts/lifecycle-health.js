import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "lifecycle-health",
  "name": "Check lifecycle service health",
  "service": "lifecycle",
  "method": "GET",
  "path": "/actuator/health",
  "global": true
});
