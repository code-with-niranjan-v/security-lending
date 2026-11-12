# Active Loans and live loan operations

All lender persistence uses Spring Data JPA. Normal local runs use the existing
PostgreSQL `lender_db`; H2 is test-only. No schema changes or demo seed data are
required by these endpoints. All routes below start with
`/api/v1/organizations/{org}` and return the existing
`{success:true,data:...,timestamp:...}` envelope. IDs in URLs are local lender
numeric IDs; `LOAN-000006` is only a display label, not a borrower loan ID.

## Register

`GET /lenddesk/loans/overview`

Parameters: `page` (default 0), `size` (default 50, maximum 200), `connectionId`,
`mslaId`, `status`, `coverage`, `q`. Invalid paging/enums return 400.

- Status: ACTIVE, PENDING_REVIEW, APPROVED_PENDING_SETTLEMENT, CLOSED, REJECTED,
  DEFAULTED, RECOVERED; omission selects all.
- Coverage: SUFFICIENT, SHORTFALL, UNKNOWN; omission selects all.
- Search: case-insensitive borrower, symbol, ISIN, agreement number, or exact local
  ID / formatted `LOAN-000006`.
- Response: `loans:{content:Row[],page,size,totalElements,totalPages}`,
  `activeLoanCount`, `totals`, `openMarginCallCount`, `openRecallCount`,
  `borrowers:[{id,label}]`, `agreements:[{id,label}]`, `asOf`.
- Counts/options are organization-wide and independent of filters. Totals cover
  ACTIVE loans and are grouped by currency. `missingValuations` identifies loans
  excluded from monetary totals, rather than representing missing values as zero.
- Register assembly batch-loads tenant loan-related records through JPA. Coverage
  filters and derived aggregates are calculated in memory before pagination;
  very large portfolios may need a materialized summary / aggregate query later.

Row response fields:

```json
{
  "id": 6,
  "connectionId": 2,
  "mslaId": 3,
  "agreementNumber": "MSLA-00124",
  "reservationId": 4,
  "borrower": "Borrower firm",
  "securityId": 42,
  "symbol": "EXAMPLE",
  "isin": "example-isin",
  "currency": "INR",
  "quantity": 100,
  "returnedQuantity": 20,
  "outstandingQuantity": 80,
  "price": 128,
  "priceAt": "2026-11-12T14:27:00Z",
  "marketValue": 10240,
  "eligibleCollateral": 10752,
  "requiredCollateral": 10752,
  "coverage": 105,
  "coverageStatus": "SUFFICIENT",
  "shortfall": 0,
  "feeBps": 240,
  "rebateBps": 180,
  "dayBasis": 360,
  "billing": "MONTHLY",
  "requiredRatio": 105,
  "recallHours": 48,
  "marginHours": 24,
  "maturity": null,
  "settledAt": "2026-11-10T14:02:11Z",
  "createdAt": "2026-11-10T14:00:00Z",
  "settlementReference": "SET-EXAMPLE",
  "status": "ACTIVE",
  "openMarginCount": 0,
  "openRecallCount": 0,
  "recallAvailableQuantity": 80,
  "canRecall": true,
  "canIssueMargin": false,
  "pendingMarginRequest": false,
  "valuationAt": "2026-11-12T14:27:00Z"
}
```

Amounts are the latest **recorded valuation snapshot**, selected by valuation
instant then ID. Cached price has its own timestamp and may be newer. No valuation
means null amounts/coverage/shortfall and UNKNOWN coverage, not a fabricated
healthy status. Fee/rebate/billing/day basis are MSLA terms, not per-loan overrides.
Returned quantity sums only SETTLED return requests. Pending returns do not reduce
outstanding. Recall capacity excludes open recall balances and PENDING/ACCEPTED
recall requests; returns in flight still require actual settlement.

## Expanded loan / workflow context

`GET /lenddesk/loans/{id}/screen`

Returns `loan:Row`, `collateral`, `returns`, `recalls`, `marginCalls`, `requests`,
`activity`, `asOf`, `unavailableFields`. Collateral entries expose position ID,
asset type, cash amount / quantity, haircut, status and creation time. Activity is
built from actual loan, return and lifecycle-request timestamps, never seeded
history. Lifecycle request payloads/source metadata are not serialized.
Cross-organization IDs return 404. The current contract does not store loan
maturity, negotiated per-loan economics, custody lots, borrower top-up inventory,
recipient overrides or amendment acknowledgments. Live UI shows these limits;
it does not submit demo terms or offer a false amendment operation.

`GET /loans/{id}/margin-preview` returns the existing indicative collateral
preview calculated with `includePending=false`: `loanMarketValue`,
`requiredCollateral`, `recognizedValue`, `shortfall`, `positions`, `priceAt`.
The margin form uses this confirmed-only preview. The command recalculates it
under the organization lock before accepting a request.

## Recall

Existing `POST /loans/{id}/recall-requests`:

```json
{"quantity":30,"reason":"Portfolio sale","clientRequestId":"unique-uuid"}
```

Positive whole quantity; reason 1–500 characters; client ID 1–120 characters.
The loan must be ACTIVE, its connection active, the MSLA recall-enabled, and the
quantity within uncommitted outstanding. Same client ID plus same details returns
the original request; changed details or excess capacity returns 409. The response
is the recorded lifecycle request with PENDING status. No loan balance is changed.
The lifecycle owner computes the deadline from MSLA `recallHours`, reports
RECALL_UPDATED, and the lender confirms the obligation to the borrower. The live
form does not offer a custom deadline that this existing contract cannot honor.

## Margin call

New `POST /loans/{id}/margin-call-requests`:

```json
{
  "amount":1600,
  "dueAt":"2026-11-13T12:00:00Z",
  "reason":"Market move",
  "clientRequestId":"unique-uuid"
}
```

Positive decimal amount (up to 18 integer / 6 fractional digits), future UTC
instant, reason 1–500, client ID 1–120. Requires ACTIVE loan/connection, a current
confirmed-collateral shortfall, amount covering that shortfall, and no open margin
call or pending margin request. Validation returns 400; state/duplicate conflicts
return 409. Identical retries return the original request without a second outbox
message. Currency comes from the loan, never a hard-coded USD form field.

The request is persisted via JPA and queued to `lender.lifecycle.commands.v1` in
the same transaction. The lifecycle obligation listener handles
MARGIN_CALL_REQUESTED, checks the loan/deadline/duplicate state, and queues a
versioned MARGIN_CALL_UPDATED (or LIFECYCLE_REQUEST_REJECTED) in its durable outbox.
The lender verifies the issuance matches requested amount/reason/deadline,
completes the request and uses the existing borrower confirmation flow. Automatic
coverage evaluation defers while an explicit request is pending, avoiding a
competing automatic issue. Existing collateral receipt/fulfillment logic remains
in charge of fulfilling the confirmed call.

Both **lender-service and lifecycle-service**, Kafka and their existing outbox
publishers must run to progress requests from PENDING to confirmed obligations.
A PENDING response means queued, not delivered to the borrower. Refresh the loan
register / reopen the workflow to see owner-confirmed state.

## Loan and return operations reused by Manage loan

| Endpoint | Body | Required state |
| --- | --- | --- |
| `POST /loans/{id}/approve` | `{}` | PENDING_REVIEW |
| `POST /loans/{id}/reject` | `{reason}` | PENDING_REVIEW |
| `POST /loans/{id}/settle` | `{reference}` | APPROVED_PENDING_SETTLEMENT, confirmed collateral and stock |
| `POST /returns/{id}/settle` | `{reference}` | PENDING return and ACTIVE loan |
| `POST /returns/{id}/reject` | `{reason}` | PENDING return |

These use existing transaction/locking, coverage, inventory and confirmation
rules. Full return settlement closes the loan and runs the existing collateral
release workflow; a partial return does not automatically release collateral.
New loans originate from borrower requests against reservations, not from a
lender button that fabricates a loan. The live New loan action explains this and
points users to the pending review/settlement tabs.

The frontend requires explicit review and confirmation before each POST, disables
repeat clicks while busy, preserves idempotency keys for identical obligation
retries, clears stale organization data, and surfaces server errors without demo
fallback. Demo workflows stay local. The PNG in `lender-ui-designs` is an
implementation screenshot; the inline original was not exposed as a file.
