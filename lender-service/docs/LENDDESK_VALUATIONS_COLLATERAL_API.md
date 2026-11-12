# Live valuations and collateral

All persistence is Spring Data JPA. Normal local runs use the existing PostgreSQL
`lender_db`. No schema migration or production demo data is added. Valuation
snapshots and per-position marks use the existing `loan_valuation.source_data`
JSON field. H2 is used only in tests.

## Run locally

Run PostgreSQL, Kafka, **lender-service (8084)**, **lifecycle-service (8085)** and
the LendDesk frontend. Both services must point at the same lender database and
Kafka broker. Keep `lender.kafka-enabled`, `lender.jobs-enabled` and
`lifecycle.kafka-enabled` enabled (their normal defaults). Lender's outbox
publisher delivers both queued commands and lifecycle reports.

1. Select **Live API** and the lender organization in LendDesk.
2. Open **Valuations**. The default list contains active loans, including those
   without a valuation. Click **Run valuation** on a loan, or run visible active
   loans (up to 100). An expanded Active Loans row also offers Run valuation.
3. The UI displays queued status and polls the run result. Successful completion
   refreshes live values. Errors such as missing security prices appear as failed
   runs with a reason, without storing a fabricated zero valuation.
4. Expand a loan on Valuations for recorded history; open **Collateral** and
   expand a position for settlement movements and position valuation snapshots.
5. Active Loans reads the same persisted results, so market value, eligible
   collateral, coverage, shortfall and margin state become available there.

Valuation uses **cached** security-master prices, and shows their timestamps.
It does not automatically fetch intraday prices. Existing lender market refresh
jobs maintain the cache. To populate/refresh a missing quote manually, the
existing endpoint is `POST /api/v1/securities/{securityId}/refresh`; the configured
market-data service must be available. Old cached prices remain timestamped;
they are not represented as fresh quotes. Missing/nonpositive prices or missing /
future quote timestamps reject a run. If a run stays pending, check the two
services, Kafka and outbox delivery. Polling times out after approximately one
minute with guidance; a pending run is never reported as completed.

Closed loans remain available under All loans / Closed for history but cannot
be revalued through this workflow. Running a valuation does not settle a loan,
move/release collateral, or create a margin call.

## Endpoint contract

All paths below are prefixed with `/api/v1/organizations/{org}` and return
`{success:true,data:...,timestamp:...}`. All URL IDs are **local numeric lender
IDs**, not formatted display labels or borrower IDs.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/lenddesk/valuations/overview` | Loan valuation register, including unvalued loans |
| GET | `/lenddesk/valuations/loans/{id}/screen` | Loan context, paginated snapshot history and latest 50 run requests |
| POST | `/loans/{id}/valuation-runs` | Queue one idempotent valuation request |
| POST | `/valuation-runs` | Queue an atomic batch of up to 100 distinct loans |
| GET | `/valuation-runs/{id}` | Poll status and failure reason |
| GET | `/collateral/overview` | Enriched positions, filters and held-value totals |
| GET | `/collateral/{id}/screen` | Position context, recorded movements and position valuation history |

The valuation register accepts the same filters/shape as the Active Loans API:
`page=0`, `size=50` (maximum 200), `connectionId`, `mslaId`, `status`, `coverage`,
`q`. See `LENDDESK_LOANS_API.md` for the row contract. Added fields are
`valuationId`, `canRunValuation`, `valuationRunPending`, `pendingValuationRunId`.
No recorded valuation means null financial values. If all active loans in a
currency are unvalued, its aggregate values are null, rather than misleading zero
totals. Partial totals retain `missingValuations`. Each currency is separate.

Snapshot history supports `page`, `size` (1–200) and returns:

```json
{
  "loan": { "id": 1, "currency": "INR" },
  "history": {
    "content": [{
      "id": 12,
      "loanId": 1,
      "valuedAt": "2026-11-12T17:00:00Z",
      "marketValue": 20000,
      "eligibleCollateral": 20000,
      "requiredCollateral": 21000,
      "coverage": 100,
      "shortfall": 1000,
      "status": "SHORTFALL",
      "price": 200,
      "priceAt": "2026-11-12T16:59:00Z",
      "quantity": 100,
      "collateral": [{
        "positionId": 1,
        "assetType": "CASH",
        "symbol": "Cash",
        "price": 1,
        "priceAt": "2026-11-12T17:00:00Z",
        "marketValue": 20000,
        "haircut": 0,
        "recognizedValue": 20000,
        "status": "POSTED"
      }]
    }],
    "page": 0, "size": 50, "totalElements": 1, "totalPages": 1
  },
  "runs": [],
  "asOf": "2026-11-12T17:00:01Z"
}
```

Older valuations may lack price, snapshot quantity or per-position marks; those
fields stay null / empty. History is not reconstructed from today's positions.

### Commands and polling

Single-loan request:

```json
{"clientRequestId":"unique-client-uuid"}
```

Batch request:

```json
{"loanIds":[1,2],"clientRequestId":"unique-batch-uuid"}
```

Single client IDs are 1–120 characters; batch IDs 1–80. Batch child IDs include
the loan ID. Same client ID and loan returns the same run even after completion
or failure. Reuse for a different loan/action conflicts. A different client ID
while one run is pending returns that pending run rather than creating competing
work. Run again after completion/failure with a new ID to create a fresh snapshot.
The frontend preserves keys on uncertain POST retries.

The result is one run (or an array for a batch):

```json
{
  "id": 25,
  "loanId": 1,
  "status": "PENDING",
  "createdAt": "2026-11-12T17:00:00Z",
  "failureReason": null
}
```

States: PENDING/ACCEPTED, COMPLETED, REJECTED. REJECTED refers to the **valuation
run**, not rejection of the loan. Failed runs expose `failureReason`. Invalid
input returns 400; wrong tenant/ID 404; ineligible loans or conflicting key reuse
409. Batch validation is atomic; an invalid/ineligible loan rolls back the batch.

The command and run request are saved in one lender transaction. Lifecycle-service
consumes VALUATION_REQUESTED, takes the same organization lock as settlement,
checks run identity and calculates from the current loan and collateral state.
It saves an immutable valuation with its own unique owner reference and a durable
VALUATION_REPORTED outbox event. Redelivery checks the deterministic run event ID.
The lender matches the request, completes it and forwards the confirmation once.
Missing prices/invalid states queue a failure report instead. Nothing is marked
COMPLETED merely because Kafka accepted a command.

Calculations use six decimal places:

- Loan value = outstanding quantity × the loan security's own cached price.
- Cash collateral value = cash amount; security collateral value = **that
  collateral security's own price** × its pledged quantity.
- Eligible value = collateral market value × (1 − haircut).
- Include POSTED and RELEASE_REQUESTED positions; exclude PLEDGE_PENDING,
  RELEASED and REJECTED. A requested release remains encumbered until confirmed.
- Required collateral = loan value × MSLA coverage ratio.
- Coverage percentage = eligible collateral / loan value × 100.
- Shortfall = max(required − eligible, 0).
- Cross-currency security collateral is rejected without an FX conversion policy.
  The existing cash collateral contract is INR-only.

### Collateral register and history

Filters: `page`, `size` (1–200), `connectionId`, `loanId`, `assetType` (CASH or
SECURITY), `status` (PLEDGE_PENDING, POSTED, RELEASE_REQUESTED, RELEASED, REJECTED),
`q` (borrower, symbol, MSLA or formatted collateral/loan ID).

Response: `positions:{content,page,size,totalElements,totalPages}`, currency-grouped
`totals`, borrower/loan options and `asOf`. Rows contain `id`, `loanId` (nullable
before loan assignment), borrower/MSLA fields, asset/security/currency,
`quantity`, `cashAmount`, cached `price`/`priceAt`, `marketValue`, percentage
`haircut`, `eligibleValue`, `includedInCoverage`, `valuationIssue`, `status`,
`settlementReference`, `createdAt`, `canRunValuation`, `loanRequiredValue`,
`loanEligibleValue`, `loanCoverage`.

These current collateral marks are **indicative**, not persisted snapshots. Missing
prices produce null values and a valuation issue. Totals cover held positions
only, retain missing-price counts and do not mix currencies. The loan's required
amount is contextual and is not summed for every position. `loanCoverage` is
whole-loan eligible collateral / required collateral × 100, so its sufficient
threshold is 100%; this differs from the loan's eligible / market value ratio.

Expanded positions return `position`, `movements` (actual recorded timestamp,
type, reference), `valuations` and a history-window description. Position history
examines the latest 100 loan snapshots and includes a position only when its mark
was actually recorded. Per-position history haircut is a fraction; register
haircut is a percentage. New snapshots retain cash/security marks and their price
timestamps. Legacy snapshots do not fabricate settlement or valuation history.

The frontend also uses the existing collateral confirm/reject/release endpoints,
with explicit review before submitting a financial settlement decision. Backend
state, inventory, account and coverage checks remain authoritative.

Validation: full lender suite, focused lifecycle valuation/obligation tests,
frontend build/lint, and an actual browser test across lender/lifecycle JPA
contexts plus an embedded Kafka broker. The existing nine fee/rebate test
failures were previously reproduced on the unchanged baseline and are unrelated
to this valuation workflow.
