> For Lendora signup/login and authenticated workspaces, use [LENDORA_AUTH.md](LENDORA_AUTH.md).
> On Windows use `start-lendora.cmd` for Lendora, BorrowDesk and LendDesk; create `lendora_db` first.
> The legacy test launcher below explicitly disables Lendora authentication for its standalone test console; do not use that mode for authenticated operation.

# Start the local lending demo

All Java service settings live in their `src/main/resources/application*.properties` files. No environment variable setup is required. Database credentials are `postgres` / `postgres`, and Kafka is enabled at `localhost:9092`.

Start PostgreSQL first. The configured databases are `market_data`, `borrower_db`, `lender_db`, `borrower_fix_db`, and `lender_fix_db`; create them once if missing.

From the project root, run `start-demo.cmd` in Command Prompt or double-click the file. The launcher opens a separate window for each application, starts Kafka through the portal, sets up missing topics, and waits for the FIX connection before starting the test frontend. The launcher starts market data with Hibernate's `update` mode to preserve existing candles.

To start the applications manually, use separate PowerShell terminals:

| Folder under D:\Project\SecurityLending | Command | Port |
| --- | --- | --- |
| kafka-ui-portal\kafka-ui-portal | `npm start` | 3001 |
| market-data-service | `.\mvnw.cmd spring-boot:run` | 8080 |
| fix-adapter-service | `.\run-lender.cmd` | 8083 |
| fix-adapter-service (second terminal) | `.\run-borrower.cmd` | 8082 |
| borrower-service | `.\mvnw.cmd spring-boot:run` | 8081 |
| lender-service | `.\mvnw.cmd spring-boot:run` | 8084 |
| lifecycle-service | `.\mvnw.cmd spring-boot:run` | 8085 |
| security-lending-test-demo | `npm run dev` | 5176 |

Before starting the Spring services, open http://127.0.0.1:3001, start Kafka, then click **Set up lending topics** near the top of **Overview**. The same action is available in Settings and Topics. It creates the 16 configured topics and preserves existing ones. It is disabled until the broker is ready. Spring does not automatically provision those topics in this project.

The FIX launchers select profiles in the same codebase; all connection and identity details remain in the properties files. By default the adapters allow borrower organization ID 1. If using another borrower organization ID, update `adapter.routes[0].organization-ids` in both adapter profile files and restart both adapters. Match lender external ID `LENDER_DEMO` and borrower connection external ID `BORROWER_DEMO` to the configured institutions.

Open http://127.0.0.1:5176 and select Check connections, then Guided workflow. Lifecycle handles margin coverage, margin calls, recalls and their deadlines, and cash-collateral rebates.

To test cash rebates, start the borrower and lender services before lifecycle so their schema migrations run first. Set the rebate rate and optional settlement frequency on the borrower MSLA proposal. Leaving the frequency blank pays the accrued rebate when the loan closes. Once a loan settles with confirmed cash collateral, lifecycle records completed days and settles any due payments in the demo ledger. It uses India calendar days, with weekly periods ending Sunday and monthly periods ending on the last calendar day.

Under **Lender → Billing & payments**, use **Run due cash rebates for a loan** with the lender loan ID to run the calculation on demand. The job also checks every minute. After the events arrive, use **View daily rebate accruals for a loan**, **View rebate settlement periods for a loan**, and **View rebate payments for a loan** on either side. Each side uses its own loan ID.

To test a loan that settled today, use **Record today's demo rebate accrual** with the lender loan ID. It records the current confirmed cash balance as today's accrual basis. A retry returns the same record, and payment follows the MSLA schedule. The demo action is enabled by `lifecycle.rebate-demo-enabled`.

Lending fees use the outstanding quantity multiplied by the most recent completed candle close as of each India calendar day's cutoff. The daily fee is that market value times the annual MSLA fee rate divided by its day-count basis. Lifecycle bills monthly or at closure according to the MSLA. Use **Lender → Billing & payments → Run due lending fees for a loan**, or **Record today's demo lending fee** to snapshot today's quantity and price. Use the lender loan ID for either action. After delivery, use **Borrower → Billing & payments → View daily lending fees for a loan** or **View lending fee bills for a loan** with the borrower loan ID.

For a recall, choose **Lender → Margin & recalls → Request loan recall** with the lender's active loan ID. When it appears in **Borrower → View recalls**, use that borrower recall ID in **Respond to recall with return**. In **Lender → Loans & returns → View returns**, find the lender return ID and use **Record return settlement**. Refresh the recall views to see partial or full fulfillment. Unfulfilled recalls become overdue at the MSLA deadline and can still be settled.

## PostgreSQL connection limits

If startup reports “sorry, too many clients already”, stop duplicate backend runs
and close unused database sessions before restarting the stack. Each service process
now uses a maximum of 5 pooled connections with 1 idle connection; restart all Java
services to apply this. The seven-process Lendora setup has a total pool limit of 35,
plus other clients and startup connections. `DB_POOL_MAX_SIZE` and `DB_POOL_MIN_IDLE`
override the per-process defaults. See [connection recovery and diagnostic SQL](LENDORA_AUTH.md#postgresql-sorry-too-many-clients-already).

## Optional service discovery and replicas

Use `start-demo.cmd -Lendora -Discovery -Replicas 2` for Eureka, Gateway and two instances of each core service. Existing single-process commands still work. See [SCALABILITY.md](SCALABILITY.md) for prerequisites, safe Kafka migration, management commands and failover tests.
