# Lender API test scripts

One standalone Axios script is provided for every lender-side endpoint/action in `security-lending-test-demo/src/api/catalog.js`, including lifecycle operations initiated from the Lender side, the Lender FIX adapter endpoints, and the market/lifecycle health checks used by the demo UI. The scripts call the real services directly; they do not depend on the React demo UI or its Vite proxy.

## Install and configure

```powershell
cd security-lending-test-demo/test-scripts
npm install
Copy-Item .env.example .env
```

Edit `.env` and set the lender organization/resource IDs returned by your services.

| Service | Default URL |
|---|---|
| Lender service | http://127.0.0.1:8084 |
| Lifecycle service | http://127.0.0.1:8085 |
| Lender FIX adapter | http://127.0.0.1:8083 |
| Market data | http://127.0.0.1:8080 |

Organization-scoped lender requests use `/api/v1/organizations/{LENDER_ORG_ID}`. Lifecycle and FIX adapter requests use their service-specific global paths. Paginated GET scripts use `PAGE` and `PAGE_SIZE`.

## Run an endpoint script

```powershell
node read-dashboard.js
node read-agreements.js
node read-loans.js
node fix-status.js
```

Each script prints its request URL, HTTP status, and response body.

## Write safety

Every non-GET script is **dry-run by default**. It prints the method, URL, and payload without sending the request. This prevents accidental approvals, rejections, settlements, inventory updates, recalls, margin calls, fee/rebate runs, buy-in requests, or payment requests during a demo.

Only after checking the target environment and payload, set `ALLOW_WRITES=true` in `.env` and run the chosen script again. Set it back to `false` immediately after the demo.

## Resource IDs

Set the relevant ID in `.env` before running an endpoint that contains `{id}`. Examples: `AGREEMENT_ID`, `LOAN_ID`, `LOCATE_ID`, `RESERVATION_ID`, `RETURN_ID`, `COLLATERAL_ID`, `MARGIN_CALL_ID`, `REBATE_ID`, `DEFAULT_ID`, `SECURITY_ID`, `INVENTORY_ID`, `NOTIFICATION_ID`, and `FIX_MESSAGE_ID`.

Use lender-side IDs for lender service endpoints. Borrower and lender IDs are not interchangeable. An accepted HTTP request may still be pending asynchronous lifecycle/Kafka processing; it does not prove that an external cash or securities transfer has settled.

## Coverage

The folder includes lender organization/user setup, organization roles, securities import/list/refresh/history, borrower connection, inventory and inventory history, agreement/locate/reservation/loan/return/collateral actions, collateral preview, valuation and margin-call lifecycle operations, recalls, fee/rebate accrual and payment operations, ledger/dashboard/audit/default/buy-in/notification reads, detail reads, and Lender FIX adapter status/inbound/outbound/publication/retry endpoints.
