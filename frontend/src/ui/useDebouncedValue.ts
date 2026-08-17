import { useEffect, useState } from 'react'

const PANEL_REFRESH_DEBOUNCE_MS = 300

export function useDebouncedValue<T>(value: T, delayMs = PANEL_REFRESH_DEBOUNCE_MS): T {
  const [settled, setSettled] = useState(value)

  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delayMs)
    return () => window.clearTimeout(timer)
  }, [value, delayMs])

  return settled
}
