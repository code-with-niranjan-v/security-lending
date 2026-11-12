import OperationalRegisterPage from '../components/OperationalRegisterPage'
import { ledgerConfig } from '../data/remainingData'
import { useDataSource } from '../hooks/useDataSource'
import LedgerDemoPage from './LedgerDemoPage'
export default function LedgerPage() {
 const {mode}=useDataSource()
 return mode==='live'?<OperationalRegisterPage config={ledgerConfig}/>:<LedgerDemoPage/>
}
