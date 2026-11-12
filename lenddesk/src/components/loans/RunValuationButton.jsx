import { useEffect, useRef, useState } from 'react'
import { lenderGet, lenderPost } from '../../api/lendDeskApi'
import { useDataSource } from '../../hooks/useDataSource'
export default function RunValuationButton({
  loanIds,
  pendingRunId = null,
  onFinished,
  label = 'Run valuation',
}) {
  const { organizationId } = useDataSource(),
    [state, setState] = useState({
      ids: pendingRunId ? [pendingRunId] : [],
      busy: false,
      message: '',
      error: false,
    })
  const alive = useRef(true),
    busy = useRef(false),
    retry = useRef(null),
    callback = useRef(onFinished)
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
    const abort = new AbortController()
    let timer,
      attempt = 0
    async function poll() {
      try {
        const runs = await Promise.all(
          ids
            .split(',')
            .map((id) =>
              lenderGet(
                `/api/v1/organizations/${organizationId}/valuation-runs/${id}`,
                abort.signal,
              ),
            ),
        )
        if (abort.signal.aborted) return
        const waiting = runs.some((r) =>
          ['PENDING', 'ACCEPTED'].includes(r.status),
        )
        if (!waiting) {
          const failed = runs.filter((r) => r.status === 'REJECTED')
          setState({
            ids: [],
            busy: false,
            error: failed.length > 0,
            message: failed.length
              ? failed
                  .map(
                    (r) =>
                      `Loan ${r.loanId}: ${r.failureReason ?? 'Valuation failed'}`,
                  )
                  .join('; ')
              : `Valuation completed for ${runs.length} loan${runs.length === 1 ? '' : 's'}.`,
          })
          retry.current = null
          busy.current = false
          callback.current?.()
          return
        }
        if (++attempt >= 30) {
          setState({
            ids: [],
            busy: false,
            error: true,
            message:
              'Valuation is still queued. Check that lifecycle-service, Kafka and the lender outbox publisher are running, then refresh to check run status.',
          })
          busy.current = false
          return
        }
        timer = setTimeout(poll, 2000)
      } catch (e) {
        if (!abort.signal.aborted) {
          setState({
            ids: [],
            busy: false,
            error: true,
            message: `Unable to check valuation status: ${e.message}. Refresh before retrying.`,
          })
          busy.current = false
        }
      }
    }
    poll()
    return () => {
      abort.abort()
      clearTimeout(timer)
    }
  }, [ids, organizationId])
  async function run() {
    if (busy.current || state.ids.length || !loanIds.length) return
    busy.current = true
    setState({
      ids: [],
      busy: true,
      error: false,
      message: 'Requesting valuation…',
    })
    const signature = JSON.stringify(loanIds)
    if (retry.current?.signature !== signature)
      retry.current = { signature, id: crypto.randomUUID() }
    try {
      const result = await lenderPost(
        `/api/v1/organizations/${organizationId}${loanIds.length === 1 ? `/loans/${loanIds[0]}/valuation-runs` : '/valuation-runs'}`,
        loanIds.length === 1
          ? { clientRequestId: retry.current.id }
          : { loanIds, clientRequestId: retry.current.id },
      )
      if (alive.current)
        setState({
          ids: (Array.isArray(result) ? result : [result]).map((r) => r.id),
          busy: true,
          error: false,
          message: 'Valuation queued. Waiting for lifecycle-service…',
        })
    } catch (e) {
      if (alive.current)
        setState({
          ids: [],
          busy: false,
          error: true,
          message: `${e.message}. Refresh before retrying if the outcome is uncertain.`,
        })
      busy.current = false
    }
  }
  return (
    <span className="valuation-run">
      <button
        className="ref-primary"
        type="button"
        disabled={
          state.busy ||
          state.ids.length > 0 ||
          !loanIds.length ||
          loanIds.length > 100
        }
        onClick={run}
      >
        {state.busy || state.ids.length ? 'Valuation pending…' : label}
      </button>
      {state.message && (
        <span
          role={state.error ? 'alert' : 'status'}
          className={state.error ? 'valuation-error' : 'valuation-notice'}
        >
          {state.message}
        </span>
      )}
    </span>
  )
}
