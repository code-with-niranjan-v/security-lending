# Workflow references

The five JPEGs below are unchanged copies of the supplied images.

| Image | Original attachment | LendDesk demo route |
| --- | --- | --- |
| `manage-locate.jpg` | `1000083030.jpg` | `/locates/LOC-017221/manage` |
| `new-msla-agreement.jpg` | `1000083031.jpg` | `/agreements/new` |
| `add-borrower.jpg` | `1000083032.jpg` | `/borrowers/new` |
| `manage-return-request.jpg` | `1000083033.jpg` | `/returns/RET-001782/manage` |
| `manage-reservation.jpg` | `1000083034.jpg` | `/reservations/RES-004891/manage` |

Implementation is in `lenddesk/src/workflows`. The routes use the supplied demo
records. Drafts and reviewed decisions are stored in the browser, separate from
live APIs. Document selection stores metadata only; sample filenames are marked
as demo references. Legal, KYC, registry, FIX connectivity, receipt, and collateral
release are not simulated as completed operations.

Validation tests: `node --test lenddesk/tests/workflowValidation.test.js` from the
repository root. Native browser validation and review dialogs also protect the
editable forms. The return workflow stops at request review; receipt and release
remain disabled. Other record IDs show an unavailable-demo message.
