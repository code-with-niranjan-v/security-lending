import { Outlet } from 'react-router-dom'
import { useEffect, useState } from 'react'
import Sidebar from './Sidebar'
import Topbar from './Topbar'
import { useDataSource } from '../hooks/useDataSource'

export default function PortalLayout() {
  const [searchTerm, setSearchTerm] = useState('')
  const [compactSidebar, setCompactSidebar] = useState(() => localStorage.getItem('lenddesk-sidebar') === 'compact')
  const { mode, organizationId } = useDataSource()

  useEffect(() => {
    localStorage.setItem('lenddesk-sidebar', compactSidebar ? 'compact' : 'expanded')
  }, [compactSidebar])

  return <main className={`app-shell ${compactSidebar ? 'sidebar-compact' : ''}`}>
    <Sidebar compact={compactSidebar} onToggleCompact={() => setCompactSidebar(value => !value)} />
    <section className="workspace">
      <Topbar onSearch={setSearchTerm} />
      {mode === 'live' && <div className="live-banner">LIVE API · Lender service · Organization {organizationId}</div>}
      <Outlet context={{ searchTerm, dataMode: mode }} />
    </section>
  </main>
}
