import { useCallback, useState } from 'react'

export interface Failure {
  message: string | null
  dismiss: () => void
  report: (message: string) => void
  attempt: (message: string, action: () => Promise<unknown>) => Promise<boolean>
}

export function useFailure(): Failure {
  const [message, setMessage] = useState<string | null>(null)
  const dismiss = useCallback(() => setMessage(null), [])
  const report = useCallback((text: string) => setMessage(text), [])

  const attempt = useCallback(async (text: string, action: () => Promise<unknown>) => {
    setMessage(null)
    try {
      await action()
      return true
    } catch (error) {
      console.error(text, error)
      setMessage(text)
      return false
    }
  }, [])

  return { message, dismiss, report, attempt }
}
