import { runEndpoint } from "./_shared.js";

await runEndpoint({
  "file": "read-reservations",
  "name": "List reservations",
  "service": "lender",
  "method": "GET",
  "path": "/reservations",
  "orgScoped": true,
  "paged": true
});
