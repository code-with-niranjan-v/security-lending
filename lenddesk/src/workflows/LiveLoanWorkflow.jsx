import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useDataSource } from '../hooks/useDataSource'
import { lenderGet, lenderPost, lenderPut } from '../api/lendDeskApi'
import { LoanCard } from '../components/loans/LoanCards'
import { date, display, loanId, money, num } from '../utils/loanFormat'
import ManageLoanScreen from './ManageLoanScreen'
import { Card, Summary } from './WorkflowControls'
import './WorkflowPage.css'
import '../pages/LoansPage.css'
const istInput = (value) => new Date(new Date(value).getTime() + 330 * 60000).toISOString().slice(0, 16)
export default function LiveLoanWorkflow({ kind, id, onDismiss, onComplete, onBusyChange }) {
  const { organizationId, refreshKey, refresh } = useDataSource(),
    localId = Number(String(id).replace(/^LOAN-/, '')),
    scope = `${organizationId}:${refreshKey}:${kind}:${localId}`
  const [state, setState] = useState({ scope: '', data: null, error: '' })
  const [notice, setNotice] = useState('')
  const inline = kind === 'loan' && !onDismiss
  function completeInline(message) {
    setNotice(message)
    refresh()
  }
  useEffect(() => {
    const abort = new AbortController()
    Promise.all([
      lenderGet(
        `/api/v1/organizations/${organizationId}/lenddesk/loans/${localId}/screen`,
        abort.signal,
      ),
      kind === 'margin'
        ? lenderGet(
            `/api/v1/organizations/${organizationId}/loans/${localId}/margin-preview`,
            abort.signal,
          )
        : Promise.resolve(null),
      kind === 'margin'
        ? lenderGet(
            `/api/v1/organizations/${organizationId}/settings/margin-shortfall-guardrail`,
            abort.signal,
          )
        : Promise.resolve(null),
    ])
      .then(([data, preview, guardrail]) => {
        if (!abort.signal.aborted)
          setState({
            scope,
            data: { ...data, preview, marginGuardEnabled: guardrail?.enabled !== false },
            error: '',
          })
      })
      .catch((e) => {
        if (!abort.signal.aborted)
          setState({ scope, data: null, error: e.message })
      })
    return () => abort.abort()
  }, [organizationId, refreshKey, localId, kind, scope])
  if (state.scope !== scope)
    return <p role="status">Loading live loan workflow…</p>
  if (state.error)
    return (
      <p className="al-error" role="alert">
        Loan unavailable: {state.error}
      </p>
    )
  return (
    <LiveEditor
      key={scope}
      kind={kind}
      data={state.data}
      organizationId={organizationId}
      onDismiss={onDismiss}
      onComplete={inline ? completeInline : onComplete}
      inline={inline}
      notice={notice}
      onBusyChange={onBusyChange}
    />
  )
}
function LiveEditor({ kind, data, organizationId, onDismiss, onComplete, onBusyChange, inline, notice }) {
  const l = data.loan,
    isMargin = kind === 'margin',
    isRecall = kind === 'recall',
    dialog = useRef(null),
    mounted = useRef(true),
    retry = useRef(null),
    submitting = useRef(false),
    settlementReferences = useRef(new Map())
  const [quantity, setQuantity] = useState(
      Math.min(l.recallAvailableQuantity, 30000),
    ),
    [amount, setAmount] = useState(data.preview?.shortfall ?? 0),
    [dueAt, setDueAt] = useState(() =>
      istInput(Date.now() + (l.marginHours || 24) * 3600000),
    ),
    [reason, setReason] = useState(''),
    [action, setAction] = useState(inline && l.status === 'PENDING_REVIEW' ? 'approve' : ''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [success, setSuccess] = useState('')
  const selectedRelease = data.collateral.find(c => String(c.id) === action.split(':')[1] && action.startsWith('collateralRelease'))
  const [guardEnabled, setGuardEnabled] = useState(data.marginGuardEnabled !== false)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const [now] = useState(() => Date.now())
  const title = isMargin
    ? 'Issue margin call'
    : isRecall
      ? 'Create recall'
      : 'Manage loan'
  const allowed = isMargin
    ? l.status === 'ACTIVE' &&
      (!guardEnabled || Number(data.preview?.shortfall) > 0) &&
      !l.openMarginCount &&
      !l.pendingMarginRequest
    : isRecall
      ? l.canRecall
      : true
  function review(e) {
    e.preventDefault()
    setError('')
    dialog.current.showModal()
  }
  async function toggleMarginGuardrail() {
    setBusy(true)
    onBusyChange?.(true)
    setError('')
    try {
      const result = await lenderPut(
        `/api/v1/organizations/${organizationId}/settings/margin-shortfall-guardrail`,
        { enabled: !guardEnabled },
      )
      setGuardEnabled(result.enabled !== false)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
      onBusyChange?.(false)
    }
  }
  function automaticSettlementReference(action) {
    const key = `${l.id}:${action}`
    if (!settlementReferences.current.has(key)) {
      const operation = action === 'settle' ? 'SECURITY-DELIVERY' : action.startsWith('collateralConfirm:') ? 'COLLATERAL-RECEIPT' : action.startsWith('collateralRelease:') ? 'COLLATERAL-RELEASE' : 'RETURN-RECEIPT'
      const timestamp = new Date().toISOString().replace(/[-:.TZ]/g, '')
      const suffix = globalThis.crypto?.randomUUID?.().slice(0, 8) ?? Math.random().toString(36).slice(2, 10)
      settlementReferences.current.set(key, `LENDDESK-${operation}-${l.id}-${timestamp}-${suffix}`)
    }
    return settlementReferences.current.get(key)
  }
  async function submit(nextAction = action) {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    onBusyChange?.(true)
    setError('')
    let path, body
    if (isMargin || isRecall) {
      body = isMargin
        ? {
            amount: Number(amount),
            dueAt: new Date(dueAt + '+05:30').toISOString(),
            reason: reason.trim(),
          }
        : { quantity: Number(quantity), reason: reason.trim() }
      const signature = JSON.stringify(body)
      if (retry.current?.signature !== signature)
        retry.current = { signature, id: crypto.randomUUID() }
      body.clientRequestId = retry.current.id
      path = `/loans/${l.id}/${isMargin ? 'margin-call-requests' : 'recall-requests'}`
    } else {
      path = nextAction.startsWith('return:')
        ? `/returns/${nextAction.split(':')[1]}/settle`
        : nextAction.startsWith('returnReject:')
          ? `/returns/${nextAction.split(':')[1]}/reject`
          : nextAction.startsWith('collateralRelease:')
            ? `/collateral/${nextAction.split(':')[1]}/release-confirmations`
            : nextAction.startsWith('collateralReleaseReject:')
              ? `/collateral/${nextAction.split(':')[1]}/release-rejections`
          : nextAction.startsWith('collateralConfirm:')
            ? `/collateral/${nextAction.split(':')[1]}/confirm`
            : nextAction.startsWith('collateralReject:')
              ? `/collateral/${nextAction.split(':')[1]}/reject`
          : `/loans/${l.id}/${nextAction}`
      body =
        nextAction === 'reject' || nextAction.startsWith('returnReject:') || nextAction.startsWith('collateralReject:') || nextAction.startsWith('collateralReleaseReject:')
          ? { reason: reason.trim() }
          : nextAction === 'approve'
            ? {}
            : { reference: automaticSettlementReference(nextAction) }
    }
    try {
      const result = await lenderPost(
        `/api/v1/organizations/${organizationId}${path}`,
        body,
      )
      if (mounted.current) {
        const requestStatus = isMargin || isRecall
          ? `Request ${result.id} recorded: ${display(result.status)}.`
          : nextAction === 'approve' ? 'Loan approved.' : 'Loan operation completed.'
        dialog.current?.close()
        if (onComplete) {
          onComplete(requestStatus)
        } else {
          setSuccess(
            isMargin || isRecall
              ? `${requestStatus} The lifecycle service processes it asynchronously; refresh the loan register to see the confirmed obligation.`
              : 'Operation completed. Refresh the loan register to see the updated position.',
          )
        }
      }
    } catch (e) {
      if (mounted.current)
        setError(
          `${e.message}. Refresh to check the current state before retrying; an uncertain network outcome may already have been recorded.`,
        )
    } finally {
      submitting.current = false
      if (mounted.current) setBusy(false)
      onBusyChange?.(false)
    }
  }
  if (inline) {
    return <ManageLoanScreen data={data} notice={notice} action={action} reason={reason} busy={busy} error={error}
      onAction={(next) => {
        setAction(next)
        setReason('')
        setError('')
      }}
      onReason={setReason} onSubmit={submit} />
  }
  return (
    <section className={`wf-page wf-live wf-${kind}`}>
      {!onDismiss && <div className="wf-breadcrumb">
        <Link to="/loans">Loans</Link>
        <span>›</span>
        <span>{loanId(l.id)}</span>
        <span>›</span>
        {title}
      </div>}
      <header className="wf-heading">
        <h1>
          {title} · {loanId(l.id)}
        </h1>
        
      </header>
      <div className="wf-context">
        <span>
          Borrower <b>{l.borrower}</b>
        </span>
        <span>
          Security <b>{l.symbol}</b>
        </span>
        <span>
          Outstanding <b>{num(l.outstandingQuantity)}</b>
        </span>
        <span>
          MSLA <b>{l.agreementNumber}</b>
        </span>
        <span>
          Status <b>{display(l.status)}</b>
        </span>
      </div>
      {success ? (
        <div className="wf-notice" role="status">
          {success} <Link to="/loans">Back to loans</Link>
        </div>
      ) : (
        <form className="al-live-form" onSubmit={review}>
          <fieldset disabled={busy}>
            <div className="wf-columns">
              <div className="wf-main">
                <Card
                  title={
                    isMargin
                      ? 'Margin call details'
                      : isRecall
                        ? 'Recall details'
                        : 'Loan operations'
                  }
                >
                  {!allowed && (
                    <p className="al-error">
                      {isMargin
                        ? guardEnabled
                          ? 'An active loan with a current collateral shortfall and no open or pending margin call is required.'
                          : 'An active loan with outstanding quantity and no open or pending margin call is required.'
                        : 'Recall is unavailable: the agreement must permit recalls and uncommitted outstanding quantity must remain.'}
                    </p>
                  )}
                  {isMargin && (
                    <>
                      <div className="wf-notice">
                        <b>Margin shortfall guardrail: {guardEnabled ? 'ON' : 'OFF — demo mode'}</b>
                        <p>{guardEnabled ? 'A current collateral shortfall is required.' : 'This demo mode allows a positive margin call without a shortfall.'}</p>
                        <button type="button" className="wf-secondary" onClick={toggleMarginGuardrail}>
                          Turn {guardEnabled ? 'off for demo' : 'on'}
                        </button>
                      </div>
                      <label>
                        Call type
                        <input value="Collateral top-up" readOnly />
                      </label>
                      <label>
                        Requested amount ({l.currency})
                        <input
                          aria-label="Requested amount"
                          type="number"
                          step="0.01"
                          min={Math.max(0.01, guardEnabled ? Number(data.preview?.shortfall) : 0.01)}
                          required
                          value={amount}
                          onChange={(e) => setAmount(e.target.value)}
                        />
                      </label>
                      <label>
                        Response deadline (IST)
                        <input
                          aria-label="Response deadline IST"
                          type="datetime-local"
                          required
                          min={istInput(now + 60000)}
                          value={dueAt}
                          onChange={(e) => setDueAt(e.target.value)}
                        />
                      </label>
                    </>
                  )}
                  {isRecall && (
                    <>
                      <label>
                        Recall type
                        <select
                          aria-label="Recall type"
                          onChange={(e) => {
                            if (e.target.value === 'Full available')
                              setQuantity(l.recallAvailableQuantity)
                          }}
                        >
                          <option>Partial</option>
                          <option>Full available</option>
                        </select>
                      </label>
                      <label>
                        Recall quantity
                        <input
                          aria-label="Recall quantity"
                          type="number"
                          min="1"
                          step="1"
                          max={l.recallAvailableQuantity}
                          required
                          value={quantity}
                          onChange={(e) => setQuantity(e.target.value)}
                        />
                      </label>
                      <p>
                        Available for recall: {num(l.recallAvailableQuantity)}{' '}
                        shares, after open and pending recalls. The lifecycle
                        owner sets the return deadline using the MSLA notice
                        period of {l.recallHours} hours.
                      </p>
                    </>
                  )}
                  {!isMargin && !isRecall && (
                    <>
                      <p>
                        Review the current loan and settle confirmed borrower
                        requests. Loan-specific amendments are not
                        supported by the current contract; MSLA terms are
                        read-only here.
                      </p>
                      <label>
                        Operation
                        <select
                          aria-label="Live loan operation"
                          required
                          value={action}
                          onChange={(e) => setAction(e.target.value)}
                        >
                          <option value="">Select operation</option>
                          {l.status === 'PENDING_REVIEW' && (
                            <>
                              <option value="approve">Approve loan</option>
                              <option value="reject">Reject loan</option>
                            </>
                          )}
                          {l.status === 'APPROVED_PENDING_SETTLEMENT' && (
                            <option value="settle">
                              Confirm securities transfer to borrower
                            </option>
                          )}
                          {data.collateral.filter((c) => c.status === 'PLEDGE_PENDING').map((c) => <option key={`confirm-${c.id}`} value={`collateralConfirm:${c.id}`}>Confirm collateral receipt {c.id}</option>)}
                          {data.collateral.filter((c) => c.status === 'PLEDGE_PENDING').map((c) => <option key={`reject-collateral-${c.id}`} value={`collateralReject:${c.id}`}>Reject collateral pledge {c.id}</option>)}
                          {data.collateral.filter(c => c.status === 'RELEASE_REQUESTED').map(c => <option key={`release-${c.id}`} value={`collateralRelease:${c.id}`}>Accept collateral release {c.id} ({c.assetType === 'CASH' ? money(c.requestedReleaseCash ?? c.cashAmount) : `${num(c.requestedReleaseQuantity ?? c.quantity)} units`})</option>)}
                          {data.collateral.filter(c => c.status === 'RELEASE_REQUESTED').map(c => <option key={`release-reject-${c.id}`} value={`collateralReleaseReject:${c.id}`}>Reject collateral release {c.id}</option>)}
                          {data.returns
                            .filter((r) => r.status === 'PENDING')
                            .map((r) => (
                              <option key={r.id} value={`return:${r.id}`}>
                                Confirm receipt of return {r.id} (
                                {num(r.quantity)} shares)
                              </option>
                            ))}
                          {data.returns
                            .filter((r) => r.status === 'PENDING')
                            .map((r) => (
                              <option
                                key={`reject${r.id}`}
                                value={`returnReject:${r.id}`}
                              >
                                Reject return {r.id} ({num(r.quantity)} shares)
                              </option>
                            ))}
                        </select>
                      </label>
                      {data.collateral.some(c => c.status === 'RELEASE_REQUESTED') && <div className="wf-notice">
                        <b>Borrower collateral release requests</b>
                        {data.collateral.filter(c => c.status === 'RELEASE_REQUESTED').map(c => <p key={c.id}>Lot {c.id}: requested {c.assetType === 'CASH' ? money(c.requestedReleaseCash ?? c.cashAmount) : `${num(c.requestedReleaseQuantity ?? c.quantity)} units`}, held {c.assetType === 'CASH' ? money(c.cashAmount) : `${num(c.quantity)} units`}.</p>)}
                        <p>Accepting checks current prices and required coverage in the backend. If the excess has fallen, acceptance is blocked and the request can be rejected.</p>
                      </div>}
                      {!onDismiss && <div className="al-live-actions">
                        {l.status === 'ACTIVE' && (
                          <Link to={`/margin-calls/${loanId(l.id)}/issue`}>
                            Issue margin call
                          </Link>
                        )}
                        {l.canRecall && (
                          <Link to={`/recalls/${loanId(l.id)}/new`}>
                            Create recall
                          </Link>
                        )}
                        <Link to={`/agreements/${l.mslaId}`}>View MSLA</Link>
                      </div>}
                    </>
                  )}
                  {(isMargin ||
                    isRecall ||
                    action === 'reject' ||
                    action.startsWith('returnReject:') || action.startsWith('collateralReleaseReject:')) && (
                    <label>
                      Reason / borrower message
                      <textarea
                        aria-label="Reason"
                        required
                        maxLength={500}
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                      />
                    </label>
                  )}
                  <p>
                    LendDesk automatically assigns an internal audit reference for settlement actions. Confirm collateral only after a custody receipt is verified. Confirming the securities transfer records the lender-to-borrower settlement, moves reserved inventory to on-loan inventory, and activates the loan; it does not itself perform a custodian transfer.
                  </p>
                </Card>
                <Card
                  title={
                    isMargin ? 'Existing call history' : 'Pending obligations'
                  }
                >
                  <table className="wf-table">
                    <thead>
                      <tr>
                        <th>Type / ID</th>
                        <th>Amount / quantity</th>
                        <th>Due IST</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(isMargin ? data.marginCalls : data.recalls).map((x) => (
                        <tr key={x.id}>
                          <td>
                            {isMargin ? 'Margin' : 'Recall'} {x.id}
                          </td>
                          <td>
                            {isMargin
                              ? money(x.amount, l.currency)
                              : num(x.quantity - x.fulfilledQuantity)}
                          </td>
                          <td>{date(x.dueAt)}</td>
                          <td>{display(x.status)}</td>
                        </tr>
                      ))}
                      {data.requests.map((x) => (
                        <tr key={`q${x.id}`}>
                          <td>
                            {display(x.type)} {x.id}
                          </td>
                          <td>—</td>
                          <td>{date(x.createdAt)}</td>
                          <td>{display(x.status)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {!(isMargin ? data.marginCalls : data.recalls).length &&
                    !data.requests.length && <p>No recorded obligations.</p>}
                </Card>
              </div>
              <aside className="wf-aside">
                <Summary
                  title="Loan & coverage summary"
                  rows={[
                    ['Loan', loanId(l.id)],
                    ['Borrower', l.borrower],
                    ['Security', l.symbol],
                    ['Currency', l.currency],
                    ['Outstanding', num(l.outstandingQuantity)],
                    ['Valuation IST', date(l.valuationAt)],
                    ['Recorded market value', money(l.marketValue, l.currency)],
                    [
                      'Recorded eligible collateral',
                      money(l.eligibleCollateral, l.currency),
                    ],
                    ['Required ratio', `${num(l.requiredRatio)}%`],
                  ]}
                />
                {isMargin && (
                  <LoanCard
                    title="Current collateral preview"
                    rows={[
                      [
                        'Loan value',
                        money(data.preview.loanMarketValue, l.currency),
                      ],
                      [
                        'Eligible collateral',
                        money(data.preview.recognizedValue, l.currency),
                      ],
                      [
                        'Required collateral',
                        money(data.preview.requiredCollateral, l.currency),
                      ],
                      ['Shortfall', money(data.preview.shortfall, l.currency)],
                      ['Price IST', date(data.preview.priceAt)],
                    ]}
                  />
                )}
                <Card title="Pre-submit checks">
                  <p>
                    Current loan state, duplicate obligations and outstanding
                    capacity are checked again on submission.
                  </p>
                  <p>
                    Borrower top-up inventory, custody instructions and
                    recipient overrides are unavailable in the current backend.
                  </p>
                </Card>
              </aside>
            </div>
            <footer className="wf-footer">
              <p>
                Review this live operation before submitting.{' '}
                <small>
                  The backend validates current state under an organization
                  lock.
                </small>
              </p>
              <div>
                {onDismiss
                  ? <button type="button" onClick={onDismiss}>Cancel</button>
                  : <Link to="/loans">Cancel</Link>}
                <button
                  className="wf-primary"
                  disabled={!allowed || (!isMargin && !isRecall && !action)}
                  type="submit"
                >
                  Review{' '}
                  {isMargin
                    ? 'margin call'
                    : isRecall
                      ? 'recall'
                      : 'loan operation'}
                </button>
              </div>
            </footer>
          </fieldset>
          <dialog
            ref={dialog}
            onCancel={(e) => {
              if (busy) e.preventDefault()
            }}
          >
            <h2>
              Confirm live{' '}
              {isMargin
                ? 'margin call'
                : isRecall
                  ? 'recall'
                  : 'loan operation'}
            </h2>
            <p>
              {loanId(l.id)} · {l.borrower} · {l.symbol}
            </p>
            <p>
              {isMargin
                ? `${money(amount, l.currency)} by ${dueAt.replace('T', ' ')} IST`
                : isRecall
                  ? `${num(quantity)} shares`
                  : selectedRelease
                    ? `${action.startsWith('collateralReleaseReject:') ? 'Reject' : 'Accept'} release for lot ${selectedRelease.id}: ${selectedRelease.assetType === 'CASH' ? money(selectedRelease.requestedReleaseCash ?? selectedRelease.cashAmount) : `${num(selectedRelease.requestedReleaseQuantity ?? selectedRelease.quantity)} units`}`
                    : display(action)}
            </p>
            {reason && <p>{reason}</p>}
            {(action === 'settle' || action.startsWith('return:') || action.startsWith('collateralConfirm:')) && <p>An internal settlement audit reference will be assigned automatically.</p>}
            <p>This submits a real backend operation.</p>
            {action.startsWith('return:') && (
              <p>
                Confirm only after receipt of the securities. A full return
                closes the loan and follows its existing collateral release
                workflow.
              </p>
            )}
            {error && (
              <p role="alert" className="al-error">
                {error}
              </p>
            )}
            <button
              type="button"
              disabled={busy}
              onClick={() => dialog.current.close()}
            >
              Back
            </button>
            <button
              className="wf-primary"
              type="button"
              disabled={busy}
              onClick={() => submit()}
            >
              {busy ? 'Submitting…' : 'Confirm live operation'}
            </button>
          </dialog>
        </form>
      )}
    </section>
  )
}
