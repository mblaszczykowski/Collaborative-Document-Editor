import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { initialiseTracing } from './observability/tracing'
import './styles.css'

initialiseTracing()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
