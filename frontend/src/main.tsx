import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { initialiseTracing } from './observability/tracing'
import './styles.css'

initialiseTracing()

if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  void navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' })
    .then(async () => {
      const registration = await navigator.serviceWorker.ready
      const warmed = new Set<string>()
      const warmAssets = () => {
        const urls = performance.getEntriesByType('resource').map(entry => entry.name).filter(value => {
          const url = new URL(value)
          if (url.origin !== location.origin || !url.pathname.startsWith('/assets/') || warmed.has(value)) return false
          warmed.add(value)
          return true
        })
        if (urls.length) registration.active?.postMessage({ type: 'CACHE_PUBLIC_ASSETS', urls })
      }
      warmAssets()
      // Lazy editor chunks can finish loading after the shell has installed.
      const observer = new PerformanceObserver(warmAssets)
      observer.observe({ type: 'resource', buffered: true })
    })
    .catch((error: unknown) => console.warn('Offline app shell could not be cached', error))
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
