# Live Locate Requests API

Implemented with JPA repositories, Specifications and JPQL only. Existing raw
locate endpoints and decision contracts remain compatible. These APIs do not
change MSLA terms, create loans or automatically create reservations.

## Register

`GET /api/v1/organizations/{org}/lenddesk/locates/overview`

| Query parameter | Meaning |
| --- | --- |
| `page`, `size` | Zero-based page, size 1–200 (default 50), descending local locate ID. |
| `connectionId` | Local borrower-connection ID. |
| `mslaId` | Local agreement ID. |
| `securityId` | Local shared security-master ID used by this organization's locates. |
| `status` | Stored status, e.g. `PENDING`, `APPROVED`, `PARTIALLY_APPROVED`, `REJECTED`, `EXPIRED`. |
| `expiry` | `EXPIRED` (deadline ≤ now), `URGENT` (0–1 hour), `UPCOMING` (>1–24 hours), `LATER` (>24 hours). Upper bounds inclusive; exact instants are compared. |
| `q` | Literal case-insensitive borrower name/external ID, MSLA number, security symbol/name/ISIN, or exact local numeric ID (optionally `LOC-` prefixed). |

Success envelope: `{ "success": true, "data": { ... }, "timestamp": "..." }`.
`data` contains:

- `locates`: `{content: Row[], page, size, totalElements, totalPages}`.
- `pendingCount`: organization-wide count with stored `PENDING` status, independent
  of filters. May include expired pending records awaiting an owner decision.
- `activeReservationCount`: organization-wide stored `ACTIVE` reservation count.
- `borrowers`, `agreements`, `securities`: tenant-scoped filter options, each
  `{id, label}`. `statuses` contains stored organization locate statuses.
- `asOf`: server UTC instant used for expiry and availability calculations.

Illustrative row (values are examples, not seeded production data):

```json
{
  "id": 22,
  "borrowerLocateId": 17222,
  "connectionId": 4,
  "borrowerName": "Borrower firm",
  "borrowerExternalId": "BORROWER_DEMO",
  "mslaId": 7,
  "agreementNumber": "MSLA-00124",
  "securityId": 42,
  "securitySymbol": "NVDA",
  "requestedQuantity": 100000,
  "approvedQuantity": 100000,
  "reservedQuantity": 30000,
  "consumedQuantity": 20000,
  "unallocatedQuantity": 50000,
  "availableQuantity": 50000,
  "inventoryAvailableQuantity": 900000,
  "status": "APPROVED",
  "createdAt": "2026-11-12T10:00:00Z",
  "expiresAt": "2026-11-13T10:00:00Z",
  "remainingSeconds": 3600,
  "expiryBucket": "URGENT",
  "canApprove": false,
  "canReject": false
}
```

`LOC-000022` is a frontend display label for local `id:22`; it is not the remote
`borrowerLocateId`. All URLs and mutation bodies use local lender IDs.

## Quantity semantics

- `reservedQuantity`: remaining shares on `ACTIVE`/`CONSUMED` reservations. This
  excludes already consumed loan quantities, avoiding double-counting.
- `consumedQuantity`: original quantity of all non-rejected loans linked through
  the reservation, including pending review, settled and closed loans. Returning
  a loan does not automatically replenish historical locate approval capacity.
- `unallocatedQuantity = max(approved − reserved − consumed, 0)`.
- `availableQuantity` is unallocated approval only while the locate is approved
  or partially approved and its deadline is future; otherwise zero.
- `inventoryAvailableQuantity` is a **separate** physical inventory balance:
  total − reserved − loaned. Null means no inventory position, not zero.
- Unused locate approval is not a reservation or an inventory guarantee. The
  current schema allows one reservation per locate, and these reads do not
  bypass that existing restriction or other reservation checks.
- Expiry calculations do not mutate stored state or unlock inventory. Lifecycle
  confirmations own those transitions; an expired-looking ACTIVE reservation
  remains encumbered until its explicit expiry/release event is processed.

## Expanded row

`GET /api/v1/organizations/{org}/lenddesk/locates/{id}/screen`

`data` contains `locate` (Row), `reservations`, `activity`, `activityCount`, `asOf`
and `unavailableFields`.

Reservation items: `id`, `borrowerReservationId`, `quantity`, `remainingQuantity`,
`status`, `expiresAt`, signed `remainingSeconds`, and `linkedLoans` (all linked
local `id`, original `quantity`, `outstandingQuantity`, `status`). The current
schema permits at most one reservation per locate; the PNG's three reservations
are not fabricated in live mode.

Activity is the latest 20 recorded local-reference audit events, ordered by UTC
creation time and local audit ID. Each item has `id`, `resourceType`, `type`,
`reference`, `createdAt`. Audit types distinguish local IDs from remote borrower
IDs to prevent unrelated records with the same numeric reference leaking in.
New decisions record `LOCATE_DECISION_APPROVED` / `LOCATE_DECISION_REJECTED`;
owner-confirmed expiry records `LOCATE_EXPIRY_CONFIRMED`. Historical decisions
without those local-reference audits are not reconstructed. The trail does not
invent actors, reasons, amendments or exact quantity deltas.

`unavailableFields` identifies unsupported custody allocations, negotiated locate
fee, owner, historical decision notes and reservation-amendment history.

## Existing live decisions

- `POST /api/v1/organizations/{org}/locates/{id}/approve`
  with `{ "quantity": 150, "reason": "Approved partial" }`.
- `POST /api/v1/organizations/{org}/locates/{id}/reject`
  with `{ "reason": "Outside mandate" }`.

Success returns the actual persisted locate in the usual envelope. Approval
produces `APPROVED` or `PARTIALLY_APPROVED`; rejection produces `REJECTED`.
The service locks the organization and revalidates pending status, borrower
connection, agreement effective dates, expiry, requested quantity and current
inventory. A reason is required, up to 500 characters. Already-decided or invalid
business transitions return 409; bad payloads return 400; foreign IDs return 404.
No success is assumed after an uncertain network outcome; refresh before retrying.

`canApprove` / `canReject` are UI hints from a snapshot, never authorization or a
substitute for transaction-time checks. The frontend fetches fresh details on
Manage, requires review and an explicit confirmation, submits once, then refreshes
both the register and detail. Unavailable fields from the demo management form are
not sent to these APIs. Confirming approval does not reserve stock or create loans.

All reads/writes are organization-scoped; authentication and role enforcement
remain outside the repository's current implementation. Live errors never cause
a demo-data fallback. The UI uses server `asOf` for countdowns and follows all
filtered pages before local sorting; backend revalidation remains authoritative.
