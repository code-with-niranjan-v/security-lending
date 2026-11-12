import OperationalRegisterPage from '../components/OperationalRegisterPage'
import { recallConfig } from '../data/remainingData'
import { useDataSource } from '../hooks/useDataSource'
import LifecycleExceptionsPage from './LifecycleExceptionsPage'
export default function RecallsPage() {
  const { mode } = useDataSource()
  return mode === 'live' ? <OperationalRegisterPage config={recallConfig} /> : <LifecycleExceptionsPage view="recalls" />
}
