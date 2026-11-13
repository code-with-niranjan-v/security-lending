import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "market-security-master",
  "name": "Check market data security master",
  "service": "market",
  "method": "GET",
  "path": "/api/v1/security-master/get-all-securities",
  "global": true
});
