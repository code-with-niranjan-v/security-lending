# Live agreements screen API

All reads are organization-scoped and use JPA repositories / Specifications.
There is no JDBC or native SQL. Existing agreement endpoints remain compatible.
Authentication/authorization is outside the repository's current scope; organization
scoping alone is not authorization.

## Register and filters

`GET /api/v1/organizations/{org}/lenddesk/agreements/overview`

Optional query parameters:

| Parameter | Meaning |
| --- | --- |
| `page`, `size` | Zero-based page; size 1–200 (default 50). Descending local agreement ID. |
| `connectionId` | Local borrower-connection ID, not remote borrower organization ID. |
| `status` | Stored status, e.g. `ACTIVE`, `PROPOSED`, `REJECTED`. |
| `recallAllowed` | `true` or `false`. |
| `activeOn` | ISO date within the inclusive effective/expiry date interval; independent of status. |
| `q` | Case-insensitive literal match on agreement number, borrower name or external ID. |

Illustrative response (IDs and amounts are examples, not seeded production records):

```json
{
  "success": true,
  "data": {
    "agreements": {
      "content": [{
        "id": 124,
        "number": "MSLA-00124",
        "connectionId": 17,
        "borrowerName": "Everest Capital",
        "borrowerExternalId": "EVRST01",
        "status": "ACTIVE",
        "effectiveDate": "2026-01-02",
        "expiryDate": "2027-01-02",
        "feeRateBps": 30,
        "rebateSettlementFrequency": "MONTHLY",
        "coveragePercent": 105,
        "recallAllowed": true,
        "activeLoanCount": 1,
        "totalLoanCount": 2,
        "totalExposure": 10000,
        "unpricedLoanCount": 0,
        "currency": "INR",
        "creditLimit": 1000000,
        "rebateRateBps": 100,
        "billingFrequency": "MONTHLY",
        "dayBasis": 365
      }],
      "page": 0, "size": 50, "totalElements": 1, "totalPages": 1
    },
    "activeAgreementCount": 1,
    "borrowers": [{"connectionId":17,"name":"Everest Capital","externalId":"EVRST01"}],
    "statuses": ["ACTIVE"],
    "unavailableFields": ["jurisdiction","masterAgreementType","concentrationLimits","fixMessages"]
  },
  "timestamp": "2026-11-12T12:00:00Z"
}
```

The active count is all organization agreements with stored status `ACTIVE`,
independent of selected filters. `borrowers` and `statuses` are scoped filter choices.
`totalElements` is the filtered count. The frontend follows all pages for its current
filters, then sorts that complete matching set locally; changing organization,
filters or refresh aborts stale requests.

## Expanded row and tabs

`GET /api/v1/organizations/{org}/lenddesk/agreements/{id}/screen`

`id` is the numeric local ID from the register, never the formatted agreement number.
The same success envelope contains:

| Property | Contents |
| --- | --- |
| `agreement` | Same typed row as above, freshly loaded. |
| `terms` | Stored proposal terms, including margin/recall notice hours. |
| `collateralRules` | Allowed rules with local `id`, `assetType`, `securityId`, `securitySymbol`, `eligible`, `haircutPercent`, `advancePercent`, nullable `concentrationLimitPercent`. |
| `activeLoans` | Up to 20 newest active loans: `id`, `securityId`, `securitySymbol`, `status`, `originalQuantity`, `outstandingQuantity`, `marketPrice`, `marketValue`, `priceAt`, `settledAt`. |
| `activeLoanCount` | Total active loans, before preview truncation. |
| `recentActivity` | Latest 20 locates/reservations combined: `id`, `type`, `securityId`, `securitySymbol`, `quantity`, `status`, `createdAt`, `expiresAt`. Type disambiguates overlapping local IDs. |
| `recentActivityCount` | Combined full count. |
| `billing` | Up to 20 newest fees and 20 newest rebates for this agreement's loans: `id`, `loanId`, `type`, `amount`, `status`, `periodStart`, `periodEnd`, `dueDate` (rebate payment date for rebates). |
| `billingCount` | Full combined fee/rebate count. These are lifecycle projections, not generated invoices. |
| `audit` | Latest 20 local agreement proposal/decision audit events: `id`, `type`, `reference`, `createdAt`. |
| `auditCount` | Full count of these local agreement audit events. |
| `unavailableFields` | Same capability limitations as register. |

## Units and unavailable data

- Money uses the lender service's supported currency, **INR**. The USD demo values
  are never substituted into live responses.
- Decimal annual rate `0.003` becomes `feeRateBps: 30`; coverage multiplier `1.05`
  becomes `coveragePercent: 105`; haircut fraction `0.02` becomes
  `haircutPercent: 2`, `advancePercent: 98`.
- Exposure sums outstanding quantity × cached security price for `ACTIVE`,
  `APPROVED_PENDING_SETTLEMENT` and `DEFAULTED` loans. Closed/rejected loans are excluded.
- If any exposed loan lacks a price, `totalExposure` is null and `unpricedLoanCount`
  reports the count; no partial total or fabricated zero is presented. A zero
  outstanding balance has known zero value. Loan previews include quote timestamps.
- Quotes are cached, not guaranteed fresh; this read does not call market-data APIs
  or perform risk calculations. Lifecycle valuation/coverage reports remain separate.
- Only stored allowed collateral rules are returned. Exclusions, concentration caps,
  jurisdiction and master-agreement framework are not currently stored. They are not
  invented from demo rows. FIX transport details belong to the adapter.
- Unknown/foreign organization agreement IDs return 404. Invalid pagination/date/
  boolean parameters return 400. Read failures are shown in live mode without demo fallback.

No agreement creation, legal workflow, or decision mutations were introduced.
