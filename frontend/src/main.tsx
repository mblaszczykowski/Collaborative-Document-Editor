import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { initialiseTracing } from './observability/tracing'
import './styles.css'

// Śledzenie startuje PRZED zamontowaniem aplikacji, żeby objąć również pierwsze
// operacje — te przy otwarciu dokumentu, które są najczęściej najwolniejsze.
initialiseTracing()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
