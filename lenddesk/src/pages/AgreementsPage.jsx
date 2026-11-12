import { useOutletContext } from 'react-router-dom'
import { agreements } from '../data/agreementData'
import StatusBadge from '../components/StatusBadge'

const statusTone = {
  Active: 'good',
  'Under review': 'warn',
  Suspended: 'risk'
}

export default function AgreementsPage() {
  const { searchTerm, dataMode } = useOutletContext()

  const rows = agreements.filter((agreement) => {
    const haystack = `${agreement.id} ${agreement.borrower} ${agreement.type} ${agreement.jurisdiction} ${agreement.status}`.toLowerCase()
    return haystack.includes(searchTerm.toLowerCase())
  })

  return (
    <section className="page-shell">
      <div className="heading agreements-heading">
        <h1>Master agreements</h1>
        <div className="header-actions">
          <button type="button" className="outline-action">New agreement</button>
        </div>
      </div>

      <div className="panel">
        <div className="panel-title">
          <h2>Agreement register</h2>
          <small>{dataMode === 'live' ? 'Live API mode' : 'Demo mode'} · {rows.length} agreements</small>
        </div>

        <div className="scroll">
          <table>
            <thead>
              <tr>
                <th>Agreement</th>
                <th>Borrower</th>
                <th>Type</th>
                <th>Jurisdiction</th>
                <th>Loans</th>
                <th>Exposure</th>
                <th>Coverage</th>
                <th>Status</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((agreement) => (
                <tr key={agreement.id}>
                  <td><strong>{agreement.id}</strong></td>
                  <td>{agreement.borrower}</td>
                  <td>{agreement.type}</td>
                  <td>{agreement.jurisdiction}</td>
                  <td className="num">{agreement.loans}</td>
                  <td className="num">{agreement.value}</td>
                  <td className="num">{agreement.coverage}</td>
                  <td><StatusBadge tone={statusTone[agreement.status] ?? 'neutral'}>{agreement.status}</StatusBadge></td>
                  <td>{agreement.updated}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
