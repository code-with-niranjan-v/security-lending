import OperationalRegisterPage from '../components/OperationalRegisterPage'
import { feeRebateConfig } from '../data/remainingData'
import { useDataSource } from '../hooks/useDataSource'
import FeesRebatesDemoPage from './FeesRebatesDemoPage'
export default function FeesRebatesPage() {
 const {mode}=useDataSource()
 return mode==='live'?<OperationalRegisterPage config={feeRebateConfig}/>:<FeesRebatesDemoPage/>
}
