import LiveFeesRebatesPage from './LiveFeesRebatesPage'
import { useDataSource } from '../hooks/useDataSource'
import FeesRebatesDemoPage from './FeesRebatesDemoPage'
export default function FeesRebatesPage() {
  const { mode } = useDataSource()
  return mode === 'live' ? <LiveFeesRebatesPage /> : <FeesRebatesDemoPage />
}
