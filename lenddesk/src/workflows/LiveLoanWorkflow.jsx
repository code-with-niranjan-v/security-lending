import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useDataSource } from '../hooks/useDataSource'
import { lenderGet, lenderPost } from '../api/lendDeskApi'
import { LoanCard } from '../components/loans/LoanCards'
import { date, display, loanId, money, num } from '../utils/loanFormat'
import { Card, Summary } from './WorkflowControls'
import './WorkflowPage.css'
import '../pages/LoansPage.css'
const utcInput = (value) => new Date(value).toISOString().slice(0, 16)
export default function LiveLoanWorkflow({ kind, id }) {
  const { organizationId, refreshKey } = useDataSource(),
    localId = Number(String(id).replace(/^LOAN-/, '')),
    scope = `${organizationId}:${refreshKey}:${kind}:${localId}`
  const [state, setState] = useState({ scope: '', data: null, error: '' })
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
    ])
      .then(([data, preview]) => {
        if (!abort.signal.aborted)
          setState({ scope, data: { ...data, preview }, error: '' })
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
    />
  )
}
function LiveEditor({ kind, data, organizationId }) {
  const l = data.loan,
    isMargin = kind === 'margin',
    isRecall = kind === 'recall',
    dialog = useRef(null),
    mounted = useRef(true),
    retry = useRef(null)
  const [quantity, setQuantity] = useState(
      Math.min(l.recallAvailableQuantity, 30000),
    ),
    [amount, setAmount] = useState(data.preview?.shortfall ?? 0),
    [dueAt, setDueAt] = useState(() =>
      utcInput(Date.now() + (l.marginHours || 24) * 3600000),
    ),
    [reason, setReason] = useState(''),
    [reference, setReference] = useState(''),
    [action, setAction] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [success, setSuccess] = useState('')
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
      Number(data.preview?.shortfall) > 0 &&
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
  async function submit() {
    if (busy) return
    setBusy(true)
    setError('')
    let path, body
    if (isMargin || isRecall) {
      body = isMargin
        ? {
            amount: Number(amount),
            dueAt: new Date(dueAt + 'Z').toISOString(),
            reason: reason.trim(),
          }
        : { quantity: Number(quantity), reason: reason.trim() }
      const signature = JSON.stringify(body)
      if (retry.current?.signature !== signature)
        retry.current = { signature, id: crypto.randomUUID() }
      body.clientRequestId = retry.current.id
      path = `/loans/${l.id}/${isMargin ? 'margin-call-requests' : 'recall-requests'}`
    } else {
      path = action.startsWith('return:')
        ? `/returns/${action.split(':')[1]}/settle`
        : action.startsWith('returnReject:')
          ? `/returns/${action.split(':')[1]}/reject`
          : `/loans/${l.id}/${action}`
      body =
        action === 'reject' || action.startsWith('returnReject:')
          ? { reason: reason.trim() }
          : action === 'approve'
            ? {}
            : { reference: reference.trim() }
    }
    try {
      const result = await lenderPost(
        `/api/v1/organizations/${organizationId}${path}`,
        body,
      )
      if (mounted.current) {
        setSuccess(
          isMargin || isRecall
            ? `Request ${result.id} recorded: ${display(result.status)}. The lifecycle service processes it asynchronously; refresh the loan register to see the confirmed obligation.`
            : 'Operation completed. Refresh the loan register to see the updated position.',
        )
        dialog.current.close()
      }
    } catch (e) {
      if (mounted.current)
        setError(
          `${e.message}. Refresh to check the current state before retrying; an uncertain network outcome may already have been recorded.`,
        )
    } finally {
      if (mounted.current) setBusy(false)
    }
  }
  return (
    <section className={`wf-page wf-live wf-${kind}`}>
      <div className="wf-breadcrumb">
        <Link to="/loans">Loans</Link>
        <span>›</span>
        <span>{loanId(l.id)}</span>
        <span>›</span>
        {title}
      </div>
      <header className="wf-heading">
        <h1>
          {title} · {loanId(l.id)}
        </h1>
        <span className="wf-demo-tag">LIVE API</span>
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
                        ? 'An active loan with a current collateral shortfall and no open or pending margin call is required.'
                        : 'Recall is unavailable: the agreement must permit recalls and uncommitted outstanding quantity must remain.'}
                    </p>
                  )}
                  {isMargin && (
                    <>
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
                          min={Math.max(0.01, Number(data.preview?.shortfall))}
                          required
                          value={amount}
                          onChange={(e) => setAmount(e.target.value)}
                        />
                      </label>
                      <label>
                        Response deadline (UTC)
                        <input
                          aria-label="Response deadline UTC"
                          type="datetime-local"
                          required
                          min={utcInput(now + 60000)}
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
                        requests. Loan-specific amendments and maturity are not
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
                              Confirm loan settlement
                            </option>
                          )}
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
                      {(action === 'settle' ||
                        action.startsWith('return:')) && (
                        <label>
                          Settlement reference
                          <input
                            aria-label="Settlement reference"
                            required
                            maxLength={160}
                            value={reference}
                            onChange={(e) => setReference(e.target.value)}
                          />
                        </label>
                      )}
                      <div className="al-live-actions">
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
                      </div>
                    </>
                  )}
                  {(isMargin ||
                    isRecall ||
                    action === 'reject' ||
                    action.startsWith('returnReject:')) && (
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
                    Issuing a recall or margin call does not move securities or
                    release collateral. Requests use the existing lifecycle and
                    borrower confirmation channels.
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
                        <th>Due UTC</th>
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
                    ['Valuation UTC', date(l.valuationAt)],
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
                      ['Price UTC', date(data.preview.priceAt)],
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
                <Link to="/loans">Cancel</Link>
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
                ? `${money(amount, l.currency)} by ${dueAt.replace('T', ' ')} UTC`
                : isRecall
                  ? `${num(quantity)} shares`
                  : display(action)}
            </p>
            {reason && <p>{reason}</p>}
            {reference && <p>Settlement reference: {reference}</p>}
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
              onClick={submit}
            >
              {busy ? 'Submitting…' : 'Confirm live operation'}
            </button>
          </dialog>
        </form>
      )}
    </section>
  )
}
