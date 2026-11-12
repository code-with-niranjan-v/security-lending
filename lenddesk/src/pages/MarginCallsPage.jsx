import OperationalRegisterPage from '../components/OperationalRegisterPage'
import { marginCallConfig } from '../data/remainingData'
import { useDataSource } from '../hooks/useDataSource'
import LifecycleExceptionsPage from './LifecycleExceptionsPage'
export default function MarginCallsPage() {
  const { mode } = useDataSource()
  return mode === 'live' ? <OperationalRegisterPage config={marginCallConfig} /> : <LifecycleExceptionsPage view="margin-calls" />
}
