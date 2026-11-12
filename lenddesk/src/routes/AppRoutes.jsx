import { BrowserRouter, Route, Routes } from 'react-router-dom'
import PortalLayout from '../components/PortalLayout'
import AgreementsPage from '../pages/AgreementsPage'
import BorrowersPage from '../pages/BorrowersPage'
import InventoryPage from '../pages/InventoryPage'
import LocatesPage from '../pages/LocatesPage'
import ReservationsPage from '../pages/ReservationsPage'
import LoansPage from '../pages/LoansPage'
import CollateralPage from '../pages/CollateralPage'
import ReturnsPage from '../pages/ReturnsPage'
import ValuationsPage from '../pages/ValuationsPage'
import MarginCallsPage from '../pages/MarginCallsPage'
import RecallsPage from '../pages/RecallsPage'
import FeesRebatesPage from '../pages/FeesRebatesPage'
import LedgerPage from '../pages/LedgerPage'
import FixMonitorPage from '../pages/FixMonitorPage'
import AuditNotificationsPage from '../pages/AuditNotificationsPage'
import AdministrationPage from '../pages/AdministrationPage'
import AgreementDetailPage from '../pages/AgreementDetailPage'
import LoanDetailPage from '../pages/LoanDetailPage'
import OverviewPage from '../pages/OverviewPage'
import PlaceholderPage from '../pages/PlaceholderPage'
import { lazy, Suspense } from 'react'
const WorkflowForm = lazy(() => import('../workflows/WorkflowPage'))
function WorkflowPage(props) {
  return <Suspense fallback={<p role="status" style={{ padding: 20 }}>Loading workflow…</p>}><WorkflowForm {...props} /></Suspense>
}

export default function AppRoutes() {
  return <BrowserRouter><Routes><Route element={<PortalLayout />}><Route index element={<OverviewPage />} /><Route path="agreements" element={<AgreementsPage />} /><Route path="margin-calls/:loanId/issue" element={<WorkflowPage kind="margin" />} /><Route path="loans/:loanId/manage" element={<WorkflowPage kind="loan" />} /><Route path="recalls/:loanId/new" element={<WorkflowPage kind="recall" />} /><Route path="agreements/new" element={<WorkflowPage kind="agreement" />} /><Route path="borrowers/new" element={<WorkflowPage kind="borrower" />} /><Route path="locates/:locateId/manage" element={<WorkflowPage kind="locate" />} /><Route path="returns/:returnId/manage" element={<WorkflowPage kind="return" />} /><Route path="reservations/:reservationId/manage" element={<WorkflowPage kind="reservation" />} /><Route path="agreements/:agreementId" element={<AgreementDetailPage />} /><Route path="borrowers" element={<BorrowersPage />} /><Route path="inventory" element={<InventoryPage />} /><Route path="locates" element={<LocatesPage />} /><Route path="reservations" element={<ReservationsPage />} /><Route path="loans" element={<LoansPage />} /><Route path="loans/:loanId" element={<LoanDetailPage />} /><Route path="collateral" element={<CollateralPage />} /><Route path="returns" element={<ReturnsPage />} /><Route path="valuations" element={<ValuationsPage />} /><Route path="margin-calls" element={<MarginCallsPage />} /><Route path="recalls" element={<RecallsPage />} /><Route path="fees-rebates" element={<FeesRebatesPage />} /><Route path="ledger" element={<LedgerPage />} /><Route path="fix-monitor" element={<FixMonitorPage />} /><Route path="audit-notifications" element={<AuditNotificationsPage />} /><Route path="administration" element={<AdministrationPage />} /><Route path="*" element={<PlaceholderPage />} /></Route></Routes></BrowserRouter>
}
