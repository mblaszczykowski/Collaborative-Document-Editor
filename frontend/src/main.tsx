import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { initialiseTracing } from './observability/tracing'
import './styles.css'

// Tracing starts BEFORE the application mounts, so it also covers the first operations — the ones
// when a document opens, which are usually the slowest.
initialiseTracing()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
