import { useEffect, useRef, useState } from 'react'
import { lenderGet, lenderPost } from '../../api/lendDeskApi'
import { useDataSource } from '../../hooks/useDataSource'
export default function RunAccrualButton({ kind, loans, onFinished }) {
  const { organizationId } = useDataSource(),
    dialog = useRef(null),
    alive = useRef(true),
    posting = useRef(false),
    retry = useRef(null),
    callback = useRef(onFinished)
  const pending = loans
      .filter((l) => l.pendingRunId)
      .map((l) => l.pendingRunId),
    ready = loans.filter((l) => !l.pendingRunId).map((l) => l.loanId)
  const [state, setState] = useState({
    ids: pending,
    busy: false,
    message: '',
    error: false,
  })
  useEffect(() => {
    callback.current = onFinished
  }, [onFinished])
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  const ids = state.ids.join(',')
  useEffect(() => {
    if (!ids) return
    const a = new AbortController()
    let timer,
      attempt = 0
    async function poll() {
      try {
        const runs = await Promise.all(
          ids
            .split(',')
            .map((id) =>
              lenderGet(
                `/api/v1/organizations/${organizationId}/accrual-runs/${id}`,
                a.signal,
              ),
            ),
        )
        if (a.signal.aborted) return
        if (runs.every((r) => !['PENDING', 'ACCEPTED'].includes(r.status))) {
          const failed = runs.filter((r) => r.status === 'REJECTED'),
            done = runs.filter((r) => r.status === 'COMPLETED'),
            days = done.reduce((n, r) => n + (r.result?.daysRecorded ?? 0), 0),
            settlements = done.reduce(
              (n, r) =>
                n + (r.result?.billsQueued ?? r.result?.settlementsQueued ?? 0),
              0,
            )
          setState({
            ids: [],
            busy: false,
            error: failed.length > 0,
            message: `${done.length} run(s) completed: ${days} new daily accrual(s), ${settlements} settlement/bill(s) queued.${failed.length ? ' ' + failed.map((r) => `Loan ${r.loanId}: ${r.failureReason ?? 'Run failed'}`).join('; ') : ''}`,
          })
          retry.current = null
          posting.current = false
          callback.current?.()
          return
        }
        if (++attempt >= 30) {
          setState({
            ids: [],
            busy: false,
            error: true,
            message:
              'Accrual is still queued. Check lifecycle-service, Kafka and lender outbox delivery, then refresh run status.',
          })
          posting.current = false
          return
        }
        timer = setTimeout(poll, 2000)
      } catch (e) {
        if (!a.signal.aborted) {
          setState({
            ids: [],
            busy: false,
            error: true,
            message: `Run status unavailable: ${e.message}. Refresh before retrying.`,
          })
          posting.current = false
        }
      }
    }
    poll()
    return () => {
      a.abort()
      clearTimeout(timer)
    }
  }, [ids, organizationId])
  async function submit() {
    if (posting.current || !ready.length) return
    posting.current = true
    setState({
      ids: [],
      busy: true,
      error: false,
      message: 'Queuing accrual run…',
    })
    const signature = JSON.stringify(ready)
    if (retry.current?.signature !== signature)
      retry.current = { signature, id: crypto.randomUUID() }
    try {
      const runs = await lenderPost(
        `/api/v1/organizations/${organizationId}/${kind === 'FEE' ? 'fees' : 'rebates'}/accrual-runs`,
        { loanIds: ready, clientRequestId: retry.current.id },
      )
      if (alive.current) {
        dialog.current.close()
        setState({
          ids: runs.map((r) => r.id),
          busy: true,
          error: false,
          message: 'Accrual queued. Waiting for lifecycle-service…',
        })
      }
    } catch (e) {
      if (alive.current)
        setState({
          ids: [],
          busy: false,
          error: true,
          message: `${e.message}. Refresh before retrying if the outcome is uncertain.`,
        })
      posting.current = false
    }
  }
  const name = kind === 'FEE' ? 'fee' : 'rebate'
  return (
    <span className="valuation-run">
      <button
        className="ref-primary"
        type="button"
        disabled={
          state.busy ||
          state.ids.length > 0 ||
          !ready.length ||
          ready.length > 100
        }
        onClick={() => dialog.current.showModal()}
      >
        {state.busy || state.ids.length
          ? `${kind === 'FEE' ? 'Fee' : 'Rebate'} accrual pending…`
          : `Run due ${name} accrual`}
      </button>
      {ready.length > 100 && (
        <small>Filter to at most 100 loans per run.</small>
      )}
      {state.message && (
        <span role={state.error ? 'alert' : 'status'}>{state.message}</span>
      )}
      <dialog
        ref={dialog}
        className="accrual-review"
        onCancel={(e) => {
          if (state.busy) e.preventDefault()
        }}
      >
        <h2>Run due {name} accrual</h2>
        <p>
          Process {ready.length} settled loan(s) using their MSLA terms. This
          includes any unprocessed accrual days through today; existing daily
          records are retained.
        </p>
        <p>
          {kind === 'FEE'
            ? 'Fee processing can also issue bills for completed billing periods. Historical completed market prices must be available.'
            : 'Rebate processing can also queue settlement and payment events when due under the MSLA. Only confirmed cash collateral movements count.'}
        </p>
        <p>No demo rates or balances are used.</p>
        {state.error && <p role="alert">{state.message}</p>}
        <button
          type="button"
          disabled={state.busy}
          onClick={() => dialog.current.close()}
        >
          Back
        </button>
        <button
          type="button"
          className="ref-primary"
          disabled={state.busy}
          onClick={submit}
        >
          {state.busy ? 'Submitting…' : `Confirm due ${name} accrual`}
        </button>
      </dialog>
    </span>
  )
}
