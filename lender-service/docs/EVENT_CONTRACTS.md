# Lender event contracts

## Topics and trust boundaries

| Topic | Producer → consumer |
|---|---|
| `lender.commands.v1` | Lender FIX adapter → lender service |
| `lender.confirmations.v1` | Lender service → lender FIX adapter |
| `lender.events.v1` | Lender service → lifecycle/reporting consumers |
| `lender.lifecycle.commands.v1` | Lender service → lifecycle service |
| `lender.lifecycle.events.v1` | Lifecycle/payment owner → lender service |

Create `.DLT` topics for the two inbound topics with at least as many partitions as the source. The destination is explicitly configured, rather than relying on Spring Kafka's default suffix. Failed DLT publication must not count as successful recovery.

The adapter stamps Kafka headers `x-counterparty-id` and `x-fix-session` from the configured FIX route after receiving a business message. The lender requires `x-counterparty-id` and resolves a connection using **local lender organization + remote institution ID + remote borrower organization ID**. Payload organization IDs alone cannot identify a borrower institution. Broker producer permissions must keep these topics restricted to their owning integrations.

## Borrower commands

The JSON matches the existing borrower outbox:

```json
{
  "eventId":"stable-request-uuid",
  "type":"LOCATE_REQUESTED",
  "organizationId":1,
  "referenceId":100,
  "occurredAt":"2026-11-08T12:00:00Z",
  "destination":"COUNTERPARTY",
  "mslaId":10,
  "lenderExternalId":"LENDER_DEMO",
  "data":{"agreementId":10,"securityId":1,"quantity":100,"expiresAt":"2026-11-09T12:00:00Z"}
}
```

`organizationId`, `mslaId`, `referenceId` and IDs inside request data are **borrower IDs**. The lender stores its own local IDs separately. For example, borrower loan 300 can be lender loan 7; a later `LOAN_SETTLED` must reference 300, not 7.

Supported command data:

| Type | referenceId | Data |
|---|---|---|
| AGREEMENT_PROPOSED | Borrower MSLA ID | Existing AgreementRequest fields; lenderId must match this lender's public reference ID |
| LOCATE_REQUESTED | Borrower locate ID | agreementId, securityId, quantity, expiresAt |
| RESERVATION_REQUESTED | Borrower reservation ID | locateId, quantity |
| RESERVATION_RELEASE_REQUESTED | Borrower reservation ID | Remaining release intent; authoritative quantity is read from lender reservation |
| COLLATERAL_PLEDGE_REQUESTED | Borrower loan ID | positionId, allocation {assetType, securityId, quantity, cashAmount, marginCallId} |
| LOAN_REQUESTED | Borrower loan ID | reservationId, quantity, collateral array |
| COLLATERAL_RELEASE_REQUESTED | Borrower collateral-position ID | loanId |
| RETURN_REQUESTED | Borrower return ID | loanId, quantity, recallId (0 when absent) |

Pledge commands can precede the loan command. They retain borrower loan ID while local loan ID is null; receiving the loan attaches them. Staff cannot confirm collateral against a nonexistent loan. Loan review and settlement are separate operations. A known expired/unavailable reservation produces a loan business rejection instead of allocating inventory.

The lender processes borrower release requests against unconsumed reservation quantity immediately, but never releases stock tied up in a pending loan. Rejecting that loan restores its reserved quantity while the reservation remains open, or releases it if the reservation was already closed by its owner.

## Confirmations and versions

```json
{
  "eventId":"confirmation-uuid",
  "type":"LOCATE_APPROVED",
  "organizationId":1,
  "referenceId":100,
  "version":1,
  "occurredAt":"2026-11-08T12:01:00Z",
  "borrowerExternalId":"BORROWER_DEMO",
  "data":{"approvedQuantity":100,"reason":"Available"}
}
```

The lender confirmation outbox uses the remote borrower organization ID as Kafka key. `borrowerExternalId` selects the adapter route and is removed by the adapter before the borrower receives the business envelope.

All borrower-facing versions are allocated centrally by `EventService`, keyed by connection, resource type and borrower/reference ID. This includes relayed lifecycle reports. The lifecycle service must **not also publish those same resource confirmations directly to the borrower** with an independent version sequence.

Resource groups match the borrower projection: msla, locate_request, reservation, loan, loan_return, collateral_position, rebate, payment_request, plus the named external read-projection types. Source lifecycle versions are separate; the lender deduplicates them before assigning a borrower confirmation version.

## Internal owner commands

```json
{
  "eventId":"lifecycle-command-uuid",
  "type":"RECALL_REQUESTED",
  "organizationId":1,
  "referenceId":50,
  "occurredAt":"2026-11-08T12:00:00Z",
  "data":{"loanId":7,"connectionId":2,"request":{"quantity":50,"reason":"Portfolio sale","clientRequestId":"recall-one"}}
}
```

Internal `organizationId`, `referenceId`, `loanId` and `connectionId` are **local lender IDs**. Owner consumers can use the read APIs/events to obtain agreement terms, security IDs and borrower correlations. Never write to lender tables directly.

| Type | referenceId | Data |
|---|---|---|
| REGISTER_LOCATE_EXPIRY | Local locate ID | expiresAt, connectionId |
| REGISTER_RESERVATION_EXPIRY | Local reservation ID | expiresAt, revision, connectionId |
| RECALL_REQUESTED | Local lifecycle-request ID | loanId, connectionId, original request |
| REBATE_PAYMENT_REQUESTED | Local lifecycle-request ID | loanId, connectionId, request {rebateReferenceId, amount} |
| BUY_IN_REQUESTED | Local lifecycle-request ID | loanId, connectionId, request {defaultReferenceId} |

The lifecycle service persists timers and emits due instructions. Kafka itself is not a delayed-delivery scheduler. The lender only reacts to those instructions. Inventory/loan/collateral changes are also published to `lender.events.v1`; important types include LOAN_SETTLED, LOAN_RETURN_SETTLED, COLLATERAL_SETTLED and COLLATERAL_RELEASE_DECIDED, with local resource snapshots.

## Lifecycle input envelope

```json
{
  "eventId":"owner-event-uuid",
  "type":"MARGIN_CALL_UPDATED",
  "organizationId":1,
  "referenceId":90001,
  "version":1,
  "occurredAt":"2026-11-08T12:30:00Z",
  "data":{
    "loanId":7,"externalId":"MC-90001","amount":5000,"fulfilledAmount":0,
    "status":"OPEN","reason":"End-of-day shortfall","dueAt":"2026-11-09T12:30:00Z"
  }
}
```

Here `organizationId` and `data.loanId` identify local lender records. For reported views, `referenceId` is the lifecycle owner's stable external record ID. The borrower currently expects globally unique external obligation/bill IDs; configure the owner's ID generator accordingly.

| Type | referenceId / data |
|---|---|
| RESERVATION_EXPIRED | Local reservation ID; data.expectedExpiresAt must equal the registered deadline, and the deadline must have arrived |
| LOCATE_EXPIRED | Local locate ID; deadline must have arrived |
| LIFECYCLE_REQUEST_ACCEPTED / REJECTED | Local lifecycle-request ID; versions increase across both responses |
| VALUATION_REPORTED | Owner snapshot ID; loanId, loanMarketValue, requiredCollateral, recognizedCollateral, coverageRatio, shortfall |
| MARGIN_CALL_UPDATED | Owner call ID; loanId, externalId, amount, fulfilledAmount, status, reason, dueAt |
| RECALL_UPDATED | Owner recall ID; loanId, externalId, quantity, fulfilledQuantity, status, reason, dueAt |
| RECALL_DEADLINE_REACHED | Owner recall ID; expectedDueAt. The lender rechecks the current deadline and outstanding quantity before marking it OVERDUE. |
| FEE_BILL_ISSUED | Owner bill ID; loanId, amount, rate, periodStart, periodEnd, dueDate |
| ACCRUAL_REPORTED | Owner daily entry ID; loanId, date, type (FEE/REBATE), amount, baseValue, rate |
| REBATE_ACCRUED | Owner rebate ID; loanId, amount, rate, date, paymentDate |
| REBATE_PAID | Same owner rebate ID at a newer source version; loanId, amount matching accrual |
| FEE_PAYMENT_RECEIVED | Same owner bill ID at newer source version; loanId, matching amount, borrowerPaymentRequestId |
| DEFAULT_REPORTED | Owner default ID; loanId, obligationId, obligationType, reason, status |
| BUY_IN_REPORTED | Owner buy-in ID; loanId, defaultId, quantity, price, costs, collateralApplied, residualBalance, status |
| LOAN_STATUS_UPDATED | Local loan ID; status=DEFAULTED. It is an explicit owner decision, not locally inferred from a deadline |

For reports fulfilling a user-initiated owner request, include `data.lifecycleRequestId`. The lender checks its loan and marks the request complete. This prevents a pending recall request from being counted again once the owner-created recall exists. An ACCEPTED request still counts as pending until that correlated report arrives or it is rejected.

Lifecycle consumes `RECALL_REQUESTED` and queues the issuance report in the shared lender outbox. In the local demo it uses the lifecycle request ID as the owner recall reference. Lender return settlement updates recall fulfillment in the same transaction and queues a versioned `RECALL_UPDATED` confirmation. The borrower receives the confirmation through the existing FIX route.

The lender stores owner-calculated amounts without recalculating them. It translates `data.loanId` to the borrower loan ID when forwarding a report, preserves owner reference IDs, assigns the appropriate outgoing version, and adds borrower routing. Rebate payment updates the local payment view/ledger and forwards REBATE_PAID. Fee payment updates the local bill/ledger and forwards PAYMENT_CONFIRMED for the specified borrower payment request.

Statuses for margin/recall views are OPEN, PARTIALLY_FULFILLED, FULFILLED, OVERDUE or CANCELLED. Receiving a past dueAt does not create a default locally. Monetary values use INR with six decimal places; rates are decimal fractions retained to ten decimal places. Bill/accrual amendment semantics require a separate correction contract rather than rewriting a paid statement.

## Recovery boundary

DEFAULT_REPORTED and BUY_IN_REPORTED are read projections. Neither instructs custody movement, collateral liquidation, stock write-off or cash debit. Those require explicit settlement events from the lifecycle/custody owner. This implementation intentionally rejects an attempt to infer recovery settlement through a generic loan-status update.

## Replay and ordering

Inbox entries bind event ID to source and payload hash; changed content under an existing ID is rejected. Domain writes and confirmations are committed in one transaction, serialized by local lender organization. The outgoing outbox preserves order per Kafka partition key and retries publication failures without claiming success. Kafka and FIX retries can duplicate messages, so every business consumer must deduplicate independently.


### LendDesk defaults and buy-ins workspace

`GET /api/v1/organizations/{org}/lenddesk/defaults-buyins` returns tenant-scoped
screen rows containing the default and its owner-reported data, loan, borrower,
security, source recall/margin call, buy-in reports and lifecycle requests. Source
obligations and buy-ins are joined by **owner reference IDs**. Requests are joined
by their payload's `defaultReferenceId`, so defaults on the same loan do not share
pending statuses accidentally.

`POST /api/v1/organizations/{org}/defaults/{id}/buy-in-requests` accepts only
`{clientRequestId}`. An open default is required. Replays return the same request;
a second pending/accepted attempt is coalesced, and a non-failed existing buy-in
report prevents another request. Exhausted command delivery marks the request
`FAILED` and records the reason. A fresh attempt retires the old failed command so
it cannot block the new command's outbox partition. Default status and collateral
balances remain owner controlled.

`BUY_IN_REPORTED` carrying `lifecycleRequestId` must match both the request loan and
the requested owner default ID. It completes that request without inferring custody
or cash settlement. The current lifecycle module has no buy-in execution consumer;
that owner integration remains required for actual execution. The frontend reflects
queued state and the reports supplied through the existing lifecycle Kafka consumer.


### Margin-call exposure reduction returns

A borrower may include `marginCallId` (the borrower's margin-call owner reference) in `RETURN_REQUESTED.data` when returning shares to reduce a margin shortfall. The lender validates that the call belongs to the same borrower connection and loan, stores its lender-local margin-call reference on the return, and exposes the link in the LendDesk returns overview. Only after the lender settles the return does the lender apply the returned shares' market value multiplied by the MSLA coverage ratio to the call's fulfilled amount and publish a versioned `MARGIN_CALL_UPDATED` confirmation to the borrower.
