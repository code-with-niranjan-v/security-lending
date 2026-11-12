# Workflow references

The eight JPEGs below are unchanged copies of the supplied images.

| Image | Original attachment | LendDesk demo route |
| --- | --- | --- |
| `manage-locate.jpg` | `1000083030.jpg` | `/locates/LOC-017221/manage` |
| `new-msla-agreement.jpg` | `1000083031.jpg` | `/agreements/new` |
| `add-borrower.jpg` | `1000083032.jpg` | `/borrowers/new` |
| `manage-return-request.jpg` | `1000083033.jpg` | `/returns/RET-001782/manage` |
| `manage-reservation.jpg` | `1000083034.jpg` | `/reservations/RES-004891/manage` |
| `issue-margin-call.jpg` | `1000083036.jpg` | `/margin-calls/LOAN-000006/issue` |
| `manage-loan.jpg` | `1000083035.jpg` | `/loans/LOAN-000006/manage` |
| `create-recall.jpg` | `1000083037.jpg` | `/recalls/LOAN-000006/new` |

Implementation is in `lenddesk/src/workflows`. The routes use the supplied demo
records. Drafts and reviewed decisions are stored in the browser, separate from
live APIs. Document selection stores metadata only; sample filenames are marked
as demo references. Legal, KYC, registry, FIX connectivity, receipt, and collateral
release are not simulated as completed operations.

Validation tests: `node --test lenddesk/tests/*.test.js` from the
repository root. Native browser validation and review dialogs also protect the
editable forms. The return workflow stops at request review; receipt and release
remain disabled. Other record IDs show an unavailable-demo message.

The loan references use the existing repository identifier `LOAN-000006`.
Margin calls validate the eligible collateral shortfall and notification choices.
Maturity-only amendments preserve current economics; all amended terms remain
proposed pending counterparty acknowledgment. Recall notice excludes weekends
but does not model holiday calendars. Recall collateral release is an estimate
conditional on a settled return; no collateral release is executed.
