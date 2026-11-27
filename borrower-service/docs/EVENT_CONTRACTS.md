# Borrower event contracts

These are explicit internal JSON contracts for the other services to implement. No external services are assumed to exist yet. The FIX adapters map these envelopes to the bilateral FIX contract; this document does not claim that every lifecycle action has a predefined standard FIX message.

## Topics

| Topic | Direction | Purpose |
|---|---|---|
| `borrower.commands.v1` | Borrower → adapter/payment integration | Borrower requests awaiting an owner decision |
| `borrower.events.v1` | Borrower → interested consumers | Borrower audit, portfolio/trading changes, and view invalidations |
| `borrower.confirmations.v1` | Adapter/owning services → borrower | Authoritative confirmations and read projections |
| `market.prices.v1` | Market data → borrower | Optional completed-minute prices |

Topic names are configurable. Partition business messages by organization ID. Configure the matching `<topic>.DLT` topics for failed inbound messages. The consumer retries three times before dead-letter publication. A failed DLT publication must not be acknowledged as success.

The market-data service currently exposes REST and STOMP, not Kafka. The borrower therefore polls its existing REST history endpoint by default; do not assume a market Kafka producer already exists.

## Outbound command

```json
{
  "eventId":"stable-uuid-across-retries",
  "type":"LOAN_REQUESTED",
  "organizationId":1,
  "referenceId":21,
  "occurredAt":"2026-11-08T12:00:00Z",
  "destination":"COUNTERPARTY",
  "mslaId":3,
  "lenderExternalId":"LENDER_ABC",
  "data":{
    "reservationId":10,
    "quantity":100,
    "collateral":[{"assetType":"CASH","securityId":null,"quantity":0,"cashAmount":150000,"marginCallId":null}]
  }
}
```

`referenceId` identifies the borrower request resource. Preserve it through the adapter and include it in the confirmation. `lenderExternalId` is routing metadata; FIX translation/session logic belongs only to the adapter. Funding/fee-payment commands use `destination=PAYMENTS` and have no FIX session assumptions.

| Command | Reference ID | Data |
|---|---|---|
| `AGREEMENT_PROPOSED` | Borrower MSLA ID | AgreementRequest fields and collateral rules |
| `LOCATE_REQUESTED` | Borrower locate ID | agreementId, securityId, quantity, expiresAt |
| `RESERVATION_REQUESTED` | Borrower reservation ID | locateId, quantity |
| `RESERVATION_RELEASE_REQUESTED` | Borrower reservation ID | remaining quantity requested for release |
| `LOAN_REQUESTED` | Borrower loan ID | reservationId, quantity, proposed collateral |
| `COLLATERAL_PLEDGE_REQUESTED` | Borrower loan ID | positionId, allocation (including optional marginCallId) |
| `COLLATERAL_RELEASE_REQUESTED` | Borrower collateral-position ID | loanId |
| `RETURN_REQUESTED` | Borrower return ID | loanId, quantity, recallId (0 when absent), optional marginCallId for a reduce-exposure response |
| `FEE_REQUESTED` | Borrower payment-request ID | amount, feeId, clientRequestId |
| `DEPOSIT_REQUESTED`, `WITHDRAWAL_REQUESTED` | Borrower payment-request ID | amount, clientRequestId |

For a new loan, collateral requests are written before the loan-request event in the same transaction. They represent allocations for that pending loan. Downstream orchestration must correlate by loan/position ID and must not treat a pledge request as a settled pledge. The owner's confirmations determine settlement.

Kafka publication is **at least once**. Event IDs are created when writing the outbox and reused on retry. Consumers must deduplicate them. Outgoing business events are not business approvals.

## Incoming confirmation envelope

```json
{
  "eventId":"adapter-confirmation-uuid",
  "type":"RESERVATION_CONFIRMED",
  "organizationId":1,
  "referenceId":10,
  "version":1,
  "occurredAt":"2026-11-08T12:01:00Z",
  "data":{"quantity":100}
}
```

Use the same event ID across retries. `version` must increase for **the resource**, not merely the event type: reservation confirmation, update, release, rejection and expiry share one version sequence. Likewise, pledge/release events share a collateral-position sequence, and rebate accrual/payment share a rebate sequence. Source services and adapters must preserve or coordinate that version.

For a reduce-exposure response, `RETURN_REQUESTED.data.marginCallId` correlates the return with the originating margin call. The borrower reserves the requested shares and waits for `RETURN_SETTLED`; it does not locally mark the margin call fulfilled. After settlement, the authoritative margin/valuation owner must recalculate the remaining shortfall and publish a versioned `MARGIN_CALL_UPDATED` projection.

The borrower locks organization state, deduplicates event IDs, and ignores a version at or below the current resource version. Projected external records use globally unique source IDs within their resource type; locally initiated resources retain their borrower IDs. Counterparty reference versions are global. Only trusted adapter/owner services should produce to the confirmation topic; this backend exposes no public confirmation endpoint.

### Request decisions and settlement

| Type | referenceId | Required data |
|---|---|---|
| `LENDER_REFERENCE_UPDATED` | Source counterparty ID | name, externalId |
| `AGREEMENT_ACCEPTED`, `AGREEMENT_REJECTED` | Borrower MSLA ID | `{}` |
| `LOCATE_APPROVED` | Borrower locate ID | approvedQuantity |
| `LOCATE_REJECTED`, `LOCATE_EXPIRED` | Borrower locate ID | `{}` |
| `RESERVATION_CONFIRMED` | Borrower reservation ID | quantity |
| `RESERVATION_REJECTED`, `RESERVATION_RELEASED`, `RESERVATION_EXPIRED` | Borrower reservation ID | `{}` |
| `RESERVATION_UPDATED` | Borrower reservation ID | remainingQuantity, status (ACTIVE/CONSUMED/EXPIRED/RELEASED) |
| `LOAN_SETTLED` | Borrower loan ID | `{}`; occurredAt is the settlement time |
| `LOAN_REJECTED` | Borrower loan ID | `{}` |
| `RETURN_SETTLED`, `RETURN_REJECTED` | Borrower return ID | `{}` |
| `COLLATERAL_PLEDGED`, `COLLATERAL_REJECTED`, `COLLATERAL_RELEASED`, `COLLATERAL_RELEASE_REJECTED` | Borrower collateral-position ID | `{}` |
| `PAYMENT_CONFIRMED`, `PAYMENT_REJECTED` | Borrower payment-request ID | amount, exactly matching the request |
| `HOLDING_IMPORTED` | Security master ID | ownedQuantity, borrowedQuantity, averageCost |

Loan settlement makes stock available, and collateral settlement has its own event. The borrower applies `RETURN_SETTLED` to the loan and `RECALL_UPDATED` to recall fulfillment. The lender queues both confirmations when a settled return satisfies an outstanding recall.

A rejected loan frees only locally pending collateral allocations. Already confirmed collateral requires an explicit release confirmation. Reservation restoration/expiry comes from its owner through a reservation event, not a borrower-side timer. `HOLDING_IMPORTED` is an authoritative custody snapshot and must include all borrower positions at its coordinated version; it cannot reduce quantities below outstanding local pledges/order locks.

### Calculated read projections

The borrower copies these values; it does not derive fees, deadlines, margin amounts, defaults, or buy-in economics from them.

| Type | referenceId | Required data |
|---|---|---|
| `FEE_BILL_ISSUED` | Source bill ID | loanId, periodStart, periodEnd, rate, amount, dueDate |
| `ACCRUAL_REPORTED` | Source daily accrual ID | loanId, date, type (FEE/REBATE), amount, baseValue, rate |
| `REBATE_ACCRUED` | Source rebate ID | loanId, rate, amount, date, paymentDate |
| `REBATE_PAID` | Source rebate ID | amount, exactly matching the reported accrual |
| `VALUATION_REPORTED` | Source valuation snapshot ID | loanId, loanMarketValue, requiredCollateral, recognizedCollateral, coverageRatio, shortfall |
| `MARGIN_CALL_UPDATED` | Source margin-call ID | loanId, externalId, amount, fulfilledAmount, reason, status, dueAt |
| `RECALL_UPDATED` | Source recall ID | loanId, externalId, quantity, fulfilledQuantity, reason, status, dueAt |
| `DEFAULT_REPORTED` | Source default ID | loanId, obligationId, obligationType, reason, status |
| `BUY_IN_REPORTED` | Source buy-in ID | defaultId, quantity, price, costs, collateralApplied, residualBalance, status |
| `LOAN_STATUS_UPDATED` | Borrower loan ID | status (DEFAULTED/RECOVERED/CLOSED), outstandingQuantity |

Dates use ISO `YYYY-MM-DD`; timestamps use UTC ISO instants. Rates are decimal fractions: 3% is `0.03`; a 105% required coverage ratio is `1.05`. Money has up to six decimal places; rates retain up to ten in the applicable columns. All amounts in this implementation are INR; FX is not implemented.

Obligation statuses are OPEN, PARTIALLY_FULFILLED, FULFILLED, OVERDUE, or CANCELLED. Receiving a past `dueAt` does not cause borrower-side expiry/default creation. The lifecycle service must explicitly publish its state change.

Bills/daily accruals/rebate accruals are append-only entries in this initial contract. A duplicate event/version is ignored; a conflicting new source entry is rejected for reconciliation. Corrections/credit notes require an agreed extension rather than silently rewriting a paid amount.

Example external margin call:

```json
{
  "eventId":"margin-call-event-uuid",
  "type":"MARGIN_CALL_UPDATED",
  "organizationId":1,
  "referenceId":700,
  "version":1,
  "occurredAt":"2026-11-08T12:30:00Z",
  "data":{
    "loanId":21,"externalId":"MC-700","amount":5000,"fulfilledAmount":0,
    "reason":"End-of-day collateral shortfall","status":"OPEN","dueAt":"2026-11-09T12:30:00Z"
  }
}
```

The borrower responds through `POST /api/v1/organizations/1/margin-calls/700/collateral`. It cannot issue the call or mark it fulfilled. After settlement/reassessment, the owner sends `MARGIN_CALL_UPDATED` version 2.

### Optional market price event

```json
{"securityId":1,"close":1510.25,"tradeDateTimestamp":"2026-11-08T09:59:00Z"}
```

The security must already exist in the reference cache. Older quotes are ignored. The trading engine rejects missing or stale prices according to `borrower.max-price-age-seconds`; live charts remain labeled as delayed.
