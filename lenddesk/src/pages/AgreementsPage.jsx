import { Fragment, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import StatusBadge from '../components/StatusBadge'
import './AgreementsPage.css'

const mockAgreements = [
  {
    id: 'MSLA-00124',
    borrower: 'Everest Capital',
    status: 'Active',
    effectiveDate: 'Jan 2, 2024',
    expiryDate: 'Jan 2, 2026',
    fee: '30 bps',
    rebate: 'Daily',
    collateralRatio: '102.0%',
    recall: 'Enabled',
    activeLoans: 14,
    exposure: '$769,087,500',
    loanRows: [
      ['LOAN-000006', 'NVDA', '120,000', '$76,908,750', '104.7%', 'Active'],
      ['LOAN-000001', 'AAPL', '92,000', '$17,881,000', '103.2%', 'Active'],
      ['LOAN-000014', 'MSFT', '75,000', '$31,800,000', '105.1%', 'Active'],
      ['LOAN-000053', 'AMZN', '55,000', '$12,720,000', '101.9%', 'Active'],
    ],
    collateralRules: [
      ['1', 'US Equity', 'Include', '98.0% / 2.0%', '≤ 40.0%'],
      ['2', 'ETP (Equity)', 'Include', '97.5% / 2.5%', '≤ 20.0%'],
      ['3', 'US Gov’t (UST)', 'Include', '99.5% / 0.5%', '≤ 60.0%'],
      ['4', 'Corp Bond (IG)', 'Include', '98.0% / 2.0%', '≤ 30.0%'],
      ['5', 'Corp Bond (HY)', 'Exclude', '—', '—'],
    ],
    locates: [
      ['14:21:37', 'Locate', 'NVDA', '1,250,000', 'Filed'],
      ['14:18:52', 'Reservation', 'AAPL', '800,000', 'Reserved'],
      ['14:15:09', 'Locate', 'MSFT', '1,000,000', 'Filed'],
      ['14:11:20', 'Reservation', 'AMZN', '500,000', 'Reserved'],
      ['14:06:47', 'Locate', 'AAPL', '400,000', 'Filed'],
    ],
  },
  {
    id: 'MSLA-00125',
    borrower: 'Bridgewater Securities',
    status: 'Active',
    effectiveDate: 'Jan 10, 2024',
    expiryDate: 'Jan 10, 2026',
    fee: '28 bps',
    rebate: 'Daily',
    collateralRatio: '103.0%',
    recall: 'Enabled',
    activeLoans: 11,
    exposure: '$492,216,000',
    loanRows: [
      ['LOAN-000098', 'AMZN', '70,000', '$12,108,000', '104.2%', 'Active'],
      ['LOAN-000102', 'GOOGL', '45,000', '$10,390,000', '103.1%', 'Active'],
      ['LOAN-000111', 'MSFT', '65,000', '$27,448,000', '102.5%', 'Active'],
    ],
    collateralRules: [
      ['1', 'US Equity', 'Include', '98.5% / 1.5%', '≤ 35.0%'],
      ['2', 'ETP (Equity)', 'Include', '97.0% / 3.0%', '≤ 25.0%'],
      ['3', 'US Gov’t (UST)', 'Include', '99.6% / 0.4%', '≤ 50.0%'],
      ['4', 'Corp Bond (IG)', 'Include', '97.8% / 2.2%', '≤ 30.0%'],
      ['5', 'Corp Bond (HY)', 'Exclude', '—', '—'],
    ],
    locates: [
      ['13:58:02', 'Locate', 'AMZN', '320,000', 'Filed'],
      ['13:44:16', 'Reservation', 'NVDA', '200,000', 'Reserved'],
      ['13:31:09', 'Locate', 'META', '180,000', 'Filed'],
    ],
  },
  {
    id: 'MSLA-00126',
    borrower: 'Atlas Capital LP',
    status: 'Active',
    effectiveDate: 'Feb 1, 2024',
    expiryDate: 'Feb 1, 2026',
    fee: '25 bps',
    rebate: 'Monthly',
    collateralRatio: '102.5%',
    recall: 'Enabled',
    activeLoans: 12,
    exposure: '$232,104,000',
    loanRows: [
      ['LOAN-000161', 'AAPL', '32,000', '$6,192,000', '104.0%', 'Active'],
      ['LOAN-000172', 'TSLA', '28,000', '$4,860,000', '102.0%', 'Active'],
      ['LOAN-000180', 'META', '40,000', '$13,502,000', '103.5%', 'Active'],
    ],
    collateralRules: [
      ['1', 'US Equity', 'Include', '99.0% / 1.0%', '≤ 40.0%'],
      ['2', 'ETP (Equity)', 'Include', '98.5% / 1.5%', '≤ 20.0%'],
      ['3', 'US Gov’t (UST)', 'Include', '99.8% / 0.2%', '≤ 60.0%'],
      ['4', 'Corp Bond (IG)', 'Include', '98.5% / 1.5%', '≤ 25.0%'],
      ['5', 'Corp Bond (HY)', 'Exclude', '—', '—'],
    ],
    locates: [
      ['15:31:02', 'Locate', 'META', '250,000', 'Filed'],
      ['15:12:03', 'Reservation', 'AAPL', '180,000', 'Reserved'],
      ['15:03:20', 'Locate', 'MSFT', '150,000', 'Filed'],
    ],
  },
  {
    id: 'MSLA-00127',
    borrower: 'Summit Global',
    status: 'Active',
    effectiveDate: 'Feb 12, 2024',
    expiryDate: 'Feb 12, 2026',
    fee: '32 bps',
    rebate: 'Daily',
    collateralRatio: '104.0%',
    recall: 'Enabled',
    activeLoans: 7,
    exposure: '$267,465,000',
    loanRows: [
      ['LOAN-000210', 'NVDA', '26,000', '$16,120,000', '103.6%', 'Active'],
      ['LOAN-000214', 'GOOGL', '21,000', '$4,640,000', '101.8%', 'Active'],
    ],
    collateralRules: [
      ['1', 'US Equity', 'Include', '98.8% / 1.2%', '≤ 35.0%'],
      ['2', 'ETP (Equity)', 'Include', '97.5% / 2.5%', '≤ 25.0%'],
      ['3', 'US Gov’t (UST)', 'Include', '99.5% / 0.5%', '≤ 60.0%'],
      ['4', 'Corp Bond (IG)', 'Include', '98.2% / 1.8%', '≤ 30.0%'],
      ['5', 'Corp Bond (HY)', 'Exclude', '—', '—'],
    ],
    locates: [
      ['12:41:44', 'Locate', 'NVDA', '420,000', 'Filed'],
      ['12:28:15', 'Reservation', 'AAPL', '260,000', 'Reserved'],
    ],
  },
  {
    id: 'MSLA-00128',
    borrower: 'Crestview Markets',
    status: 'Active',
    effectiveDate: 'Mar 1, 2024',
    expiryDate: 'Mar 1, 2026',
    fee: '29 bps',
    rebate: 'Daily',
    collateralRatio: '102.0%',
    recall: 'Disabled',
    activeLoans: 11,
    exposure: '$421,500,000',
    loanRows: [
      ['LOAN-000310', 'AAPL', '38,000', '$7,370,000', '104.1%', 'Active'],
      ['LOAN-000322', 'MSFT', '41,000', '$17,155,000', '102.7%', 'Active'],
    ],
    collateralRules: [
      ['1', 'US Equity', 'Include', '98.5% / 1.5%', '≤ 30.0%'],
      ['2', 'ETP (Equity)', 'Include', '97.0% / 3.0%', '≤ 20.0%'],
      ['3', 'US Gov’t (UST)', 'Include', '99.6% / 0.4%', '≤ 60.0%'],
      ['4', 'Corp Bond (IG)', 'Include', '98.4% / 1.6%', '≤ 25.0%'],
      ['5', 'Corp Bond (HY)', 'Exclude', '—', '—'],
    ],
    locates: [
      ['11:18:42', 'Locate', 'MSFT', '500,000', 'Filed'],
      ['11:11:09', 'Reservation', 'GOOGL', '150,000', 'Reserved'],
    ],
  },
  {
    id: 'MSLA-00129',
    borrower: 'Orion Hedge Fund',
    status: 'Active',
    effectiveDate: 'Mar 15, 2024',
    expiryDate: 'Mar 15, 2026',
    fee: '31 bps',
    rebate: 'Weekly',
    collateralRatio: '103.0%',
    recall: 'Enabled',
    activeLoans: 6,
    exposure: '$145,065,000',
    loanRows: [
      ['LOAN-000380', 'META', '18,000', '$6,058,000', '104.6%', 'Active'],
      ['LOAN-000401', 'NVDA', '12,000', '$7,380,000', '102.1%', 'Active'],
    ],
    collateralRules: [
      ['1', 'US Equity', 'Include', '98.3% / 1.7%', '≤ 35.0%'],
      ['2', 'ETP (Equity)', 'Include', '97.7% / 2.3%', '≤ 25.0%'],
      ['3', 'US Gov’t (UST)', 'Include', '99.6% / 0.4%', '≤ 60.0%'],
      ['4', 'Corp Bond (IG)', 'Include', '98.0% / 2.0%', '≤ 30.0%'],
      ['5', 'Corp Bond (HY)', 'Exclude', '—', '—'],
    ],
    locates: [
      ['10:19:54', 'Locate', 'META', '310,000', 'Filed'],
      ['10:06:41', 'Reservation', 'TSLA', '120,000', 'Reserved'],
    ],
  }
]

const statusTone = {
  Active: 'good',
  'Under review': 'warn',
  Suspended: 'risk',
  Disabled: 'warn'
}

const recallTone = {
  Enabled: 'good',
  Disabled: 'warn'
}

export default function AgreementsPage() {
  const { searchTerm } = useOutletContext()
  const [expandedId, setExpandedId] = useState('MSLA-00124')
  const [detailTab, setDetailTab] = useState({ 'MSLA-00124': 'collateral' })

  const rows = useMemo(
    () => mockAgreements.filter((agreement) => `${agreement.id} ${agreement.borrower} ${agreement.status}`.toLowerCase().includes(searchTerm.toLowerCase())),
    [searchTerm]
  )

  const setAgreementTab = (agreementId, nextTab) => {
    setDetailTab((current) => ({ ...current, [agreementId]: nextTab }))
  }

  return (
    <section className="page-shell agreement-page-shell">
      <div className="heading agreement-heading">
        <h1>MSLA Agreements</h1>
        <span className="agreement-count">48 active</span>
        <button type="button" className="primary-action">New agreement</button>
      </div>

      <div className="agreement-filters panel">
        <div>
          <label>
            Borrower
            <select defaultValue="All borrowers">
              <option>All borrowers</option>
              <option>Everest Capital</option>
              <option>Bridgewater Securities</option>
              <option>Atlas Capital LP</option>
            </select>
          </label>
          <label>
            Status
            <select defaultValue="All statuses">
              <option>All statuses</option>
              <option>Active</option>
              <option>Under review</option>
              <option>Suspended</option>
            </select>
          </label>
          <label>
            Jurisdiction
            <select defaultValue="All jurisdictions">
              <option>All jurisdictions</option>
              <option>New York</option>
              <option>English law</option>
            </select>
          </label>
          <label>
            Date range
            <select defaultValue="Effective or expiry date">
              <option>Effective or expiry date</option>
              <option>Effective date</option>
              <option>Expiry date</option>
            </select>
          </label>
        </div>
      </div>

      <section className="agreement-workspace">
        <article className="panel agreement-register">
          <div className="scroll">
            <table className="agreement-table">
              <thead>
                <tr>
                  <th>Agreement ID</th>
                  <th>Borrower</th>
                  <th>Status</th>
                  <th>Effective date</th>
                  <th>Expiry date</th>
                  <th>Lending fee</th>
                  <th>Rebate frequency</th>
                  <th>Collateral ratio</th>
                  <th>Recall</th>
                  <th>Active loans</th>
                  <th>Exposure</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((agreement) => (
                  <Fragment key={agreement.id}>
                    <tr className={expandedId === agreement.id ? 'selected' : ''} onClick={() => setExpandedId(expandedId === agreement.id ? '' : agreement.id)}>
                      <td className="agreement-id-cell">
                        <button type="button" className="row-expander" aria-label={`Toggle ${agreement.id}`}>
                          {expandedId === agreement.id ? '⌄' : '›'}
                        </button>
                        <a href="#">{agreement.id}</a>
                      </td>
                      <td>{agreement.borrower}</td>
                      <td><StatusBadge tone={statusTone[agreement.status] ?? 'neutral'}>{agreement.status}</StatusBadge></td>
                      <td>{agreement.effectiveDate}</td>
                      <td>{agreement.expiryDate}</td>
                      <td>{agreement.fee}</td>
                      <td>{agreement.rebate}</td>
                      <td>{agreement.collateralRatio}</td>
                      <td><StatusBadge tone={recallTone[agreement.recall] ?? 'neutral'}>{agreement.recall}</StatusBadge></td>
                      <td>{agreement.activeLoans}</td>
                      <td className="agreement-exposure">{agreement.exposure}</td>
                    </tr>

                    {expandedId === agreement.id && (
                      <tr className="child-row">
                        <td colSpan="11">
                          <div className="child-table agreement-detail-panel">
                            <div className="agreement-tab-row">
                              <button
                                type="button"
                                className={((detailTab[agreement.id] ?? 'collateral') === 'collateral') ? 'is-active' : ''}
                                onClick={() => setAgreementTab(agreement.id, 'collateral')}
                              >
                                Collateral rules
                              </button>
                              <button
                                type="button"
                                className={((detailTab[agreement.id] ?? 'collateral') === 'loans') ? 'is-active' : ''}
                                onClick={() => setAgreementTab(agreement.id, 'loans')}
                              >
                                Active loans
                              </button>
                              <button
                                type="button"
                                className={((detailTab[agreement.id] ?? 'collateral') === 'activity') ? 'is-active' : ''}
                                onClick={() => setAgreementTab(agreement.id, 'activity')}
                              >
                                Recent activity
                              </button>
                            </div>

                            {((detailTab[agreement.id] ?? 'collateral') === 'collateral') && (
                              <div className="agreement-card compact-card">
                                <table className="agreement-detail-table">
                                  <thead>
                                    <tr>
                                      <th>#</th>
                                      <th>Asset type</th>
                                      <th>Eligible</th>
                                      <th>Advance / haircut</th>
                                      <th>Concentration</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {agreement.collateralRules.map(([id, type, eligibility, haircut, concentration]) => (
                                      <tr key={`${agreement.id}-${id}`}>
                                        <td>{id}</td>
                                        <td>{type}</td>
                                        <td>{eligibility === 'Include' ? <StatusBadge tone="good">Include</StatusBadge> : <StatusBadge tone="risk">Exclude</StatusBadge>}</td>
                                        <td>{haircut}</td>
                                        <td>{concentration}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            )}

                            {((detailTab[agreement.id] ?? 'collateral') === 'loans') && (
                              <div className="agreement-card compact-card">
                                <table className="agreement-detail-table">
                                  <thead>
                                    <tr>
                                      <th>Loan ID</th>
                                      <th>Security</th>
                                      <th>Qty</th>
                                      <th>Market value</th>
                                      <th>Coverage</th>
                                      <th>Rate</th>
                                      <th>Status</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {agreement.loanRows.map(([loanId, security, quantity, value, coverage, status], index) => (
                                      <tr key={`${agreement.id}-${loanId}-${index}`}>
                                        <td><a href="#">{loanId}</a></td>
                                        <td>{security}</td>
                                        <td className="num">{quantity}</td>
                                        <td className="num">{value}</td>
                                        <td>{coverage}</td>
                                        <td>{index % 2 === 0 ? '185 bps' : '74 bps'}</td>
                                        <td><StatusBadge tone={status === 'Active' ? 'good' : 'neutral'}>{status}</StatusBadge></td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            )}

                            {((detailTab[agreement.id] ?? 'collateral') === 'activity') && (
                              <div className="agreement-card compact-card">
                                <table className="agreement-detail-table">
                                  <thead>
                                    <tr>
                                      <th>Time</th>
                                      <th>Type</th>
                                      <th>Security</th>
                                      <th>Borrower</th>
                                      <th>Qty</th>
                                      <th>Status</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {agreement.locates.map(([time, type, security, quantity, status], index) => (
                                      <tr key={`${agreement.id}-${time}-${index}`}>
                                        <td>{time}</td>
                                        <td>{type}</td>
                                        <td>{security}</td>
                                        <td>{agreement.borrower}</td>
                                        <td className="num">{quantity}</td>
                                        <td><StatusBadge tone={status === 'Filed' ? 'good' : 'warn'}>{status}</StatusBadge></td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </article>
      </section>
    </section>
  )
}
