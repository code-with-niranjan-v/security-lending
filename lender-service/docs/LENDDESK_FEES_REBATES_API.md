# Live Fees & Rebates and due accrual runs

The live page now uses a dedicated JPA screen projection. The old generic
register expected `type`, `borrower`, `total`, `rule`, and `due`, while raw
`daily_accrual` records supply `accrualType` and local relationship IDs. That
mismatch caused empty Fee/Rebate tabs and blank cells; its counters/metrics were
also demo constants. Live mode now maps real types, joins borrower/loan/MSLA
context and calculates real counts/totals. Demo mode stays local.

## Using the page locally

After pulling, restart lender-service and lifecycle-service, and refresh/rebuild
the frontend. Keep PostgreSQL and Kafka running. Both services use the existing
lender database; no new schema migration is required. Persistence is JPA-only.

Open **Live API → Fees & Rebates**. Select a loan (or leave All loans) and use:

- **Run due fee accrual**: compute unprocessed fee days for eligible settled
  loans; issue fee bills when their billing period is complete / the loan closes.
- **Run due rebate accrual**: compute unprocessed rebate days from confirmed cash
  movements; queue settlement/payment events when due under the MSLA.

Both actions show a review dialog explaining their settlement effects before a
POST. Processing is asynchronous through Kafka, with polling and a persistent
Recent accrual runs table. A run may correctly report zero new daily records when
already up to date. Repeated runs never add a second record for the same loan/day.
The frontend disables duplicate clicks and retains request IDs on uncertain
transport retries. There is no automatic retry of a POST.

Fee accrual requires the configured market-data service (default
`lifecycle.market-data-url=http://localhost:8080`) to return historical completed
candles. A cached spot price or a new valuation alone is insufficient for
historical fee accrual. Missing market data is shown as a failed run with its
reason; the page does not substitute demo prices. Rebates require actual
PLEDGE_CONFIRMED / RELEASE_CONFIRMED cash movement history. A cash position with
no recorded settlement movement does not establish a historical cash balance.

If a run stays pending, check lifecycle-service, Kafka and lender outbox delivery.
The UI stops polling after approximately one minute and provides guidance.
Normal defaults enable scheduled processing as well as manual processing. To run
only manually, `lifecycle.accrual-jobs-enabled=false` disables the scheduled fee
and rebate jobs while leaving Kafka command handling enabled.

## Accrual and settlement timing

Business dates use Asia/Kolkata, shown on the page.

- Existing due processing includes the current date's first accrual snapshot.
  It records at most one row per loan/day and retains existing rows; it does not
  revalue a recorded daily accrual each time the button is pressed.
- Historical prices, returns and cash movements are limited to the earlier of
  the relevant day end, loan close time and run instant. Future movements/quotes
  are not included in a current-day run.
- For an open loan, billing/settlement periods must have finished before today.
  Thus a DAILY rebate accrued today is not paid ahead of its next-day payment
  date. Actual loan closure forces final period settlement under the existing
  closing workflow.
- Fee bills remain DUE until confirmed paid. The rebate workflow emits its
  existing REBATE_ACCRUED / REBATE_PAID events when settlement is due. Manual
  processing uses the same account/ledger behavior as scheduled processing.
- Current-day snapshots are not retroactively corrected when later events occur;
  historical corrections require the existing explicit correction contract.

## Endpoints

Prefix all paths with `/api/v1/organizations/{org}`. All responses use the
standard `{success:true,data:...,timestamp:...}` envelope. URL/body loan and run
IDs are local numeric lender IDs, not formatted labels or borrower IDs.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/lenddesk/fees-rebates/overview` | Enriched daily accruals, settlements, real totals, eligible loans and recent runs |
| POST | `/fees/accrual-runs` | Queue due fee accrual for a batch |
| POST | `/rebates/accrual-runs` | Queue due rebate accrual for a batch |
| POST | `/loans/{id}/fees/accrual-runs` | Queue a single fee run |
| POST | `/loans/{id}/rebates/accrual-runs` | Queue a single rebate run |
| GET | `/accrual-runs/{id}` | Poll status, result and failure reason |

Register parameters: `page=0`, `size=50` (1–200), `tab=ALL` (ALL/FEE/REBATE/DUE),
optional `loanId`, optional case-insensitive `q` (borrower, symbol, agreement or
formatted loan ID). Invalid paging/tab returns 400; foreign organization IDs
return 404 / empty scoped filters.

Response fields:

- `records:{content,page,size,totalElements,totalPages}`.
- `counts:{ALL,FEE,REBATE,DUE}`: real counts within loan/text filters, independent
  of selected tab. ALL/FEE/REBATE count daily records; DUE counts outstanding
  DUE fee bills and ACCRUED rebate settlements. Paid records are not pending dues.
- `totals`: separate currency entries with `feeToday`, `rebateToday`, `feeTotal`,
  `rebateTotal`. These sum recorded daily accruals, never daily rows plus bills.
  Net recorded accrual is fee total minus rebate total, not unpaid receivable.
- `businessDate`, `businessZone`, `asOf`.
- `loans:[{id,label}]`: real tenant loan options, even if no accruals exist yet.
- `eligibleLoans:[{loanId,kind,borrower,symbol,processedThrough,pendingRunId}]`.
  Settled ACTIVE/CLOSED loans with positive MSLA rates are eligible. Finalized
  closed fees are omitted; other already-processed runs can return zero.
- `runs`: latest 30 manual fee/rebate requests, scoped by selected loan. Text
  search and selected tab do not hide the run history.

Example daily row (values illustrative):

```json
{
  "id": 13,
  "key": "accruals:13",
  "category": "accruals",
  "loanId": 1,
  "borrower": "Borrower firm",
  "symbol": "EXAMPLE",
  "currency": "INR",
  "mslaId": 2,
  "agreementNumber": "MSLA-EXAMPLE",
  "type": "REBATE",
  "entryDate": "2026-11-12",
  "rateBps": 100,
  "amount": 4.109589,
  "baseValue": 150000,
  "accruedTotal": 8.219178,
  "settlementRule": "AT_CLOSE",
  "dayBasis": 365,
  "dueDate": null,
  "status": "REPORTED",
  "settlementStatus": "Not issued",
  "settlementId": null
}
```

Rates are annual fractional rates × 10,000 (0.01 = 100 bps). Money preserves up
to six decimals on the page. The Daily amount column belongs to the shown entry
date, not automatically today. Recorded accrued total sums all available daily
rows for that loan/type; it is not an unpaid invoice balance. Current MSLA terms
are labeled as current in expanded details, rather than claimed as historical
terms. Matching actual bill/settlement dates and status take precedence over an
indicative contractual date. An open AT_CLOSE agreement displays **At loan close**
without fabricating a date.

DUE rows use category `fees` or `rebates` and unique category-prefixed keys,
actual bill/settlement amount, period dates and due/payment date. A legacy bill
with no daily records has no fabricated daily base or accrued total; the UI says
Not recorded. An empty DUE tab means no outstanding settlement records, not a
broken Fee/Rebate filter.

## Run requests and results

Batch body (1–100 distinct loans, client ID max 80 characters):

```json
{"loanIds":[1,2],"clientRequestId":"unique-batch-uuid"}
```

Single body (client ID max 120):

```json
{"clientRequestId":"unique-run-uuid"}
```

Single returns one run; batch returns an array. Each batch child has a client ID
including its loan ID. Same client ID plus same loan/type returns the original
request; reuse for another loan/type returns 409. A second key while that
loan/type is pending returns its existing pending run. A fresh key after a
terminal result requests a new idempotent pass. Invalid inputs return 400, wrong
tenant/ID 404, ineligible loan/rate or conflicting key 409. Batch validation is
atomic.

```json
{
  "id": 3,
  "loanId": 1,
  "kind": "FEE",
  "status": "COMPLETED",
  "createdAt": "2026-11-12T17:00:00Z",
  "failureReason": null,
  "result": {
    "loanId": 1,
    "processedThrough": "2026-11-12",
    "daysRecorded": 0,
    "billsQueued": 0,
    "billedAmount": 0
  }
}
```

Rebate results use `settlementsQueued` and `queuedSettlementAmount`. Run states
are PENDING/ACCEPTED, COMPLETED or REJECTED. Rejection refers to the run, not to
the loan, and exposes a failure reason. No business work is complete merely
because Kafka accepted the queued command.

Lender saves the request and command outbox in one transaction. Lifecycle-service
handles FEE_ACCRUAL_REQUESTED / REBATE_ACCRUAL_REQUESTED under the organization
lock. Computation and the ACCRUAL_RUN_COMPLETED outbox report commit together.
On a domain/market-data failure, computation rolls back before a separate failure
report transaction. Deterministic report event IDs and unique loan/day records
protect redelivery and parallel scheduled/manual jobs. Lender validates the
reported loan/type before storing the result. Existing accrual/bill/payment
projections supply the register; there is no second financial calculation in the
frontend or lender screen service.

Validation includes full lender and lifecycle suites, rollback/report unit tests,
frontend build/lint, and browser tests through a real Kafka broker and two JPA
service contexts: both initial run types, populated tabs and fields, explicit
confirmation, repeat-run idempotence, settlement timing, and tenant isolation.
