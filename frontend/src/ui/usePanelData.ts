import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../api'
import { useDebouncedValue } from './useDebouncedValue'
import { useFailure, type Failure } from './useFailure'

export interface PanelData<T> {
  data: T
  loading: boolean
  refresh: () => Promise<void>
  failure: Failure
}

export function usePanelData<T>({ load, revision, initial, describe }: {
  load: () => Promise<T>
  revision: number
  initial: T
  describe: string
}): PanelData<T> {
  const [data, setData] = useState<T>(initial)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const actions = useFailure()
  const latest = useRef(0)
  const { dismiss: dismissAction } = actions
  const dismiss = useCallback(() => { dismissAction(); setLoadError(null) }, [dismissAction])

  const refresh = useCallback(async () => {
    const ticket = ++latest.current
    setLoading(true)
    try {
      const loaded = await load()
      if (ticket !== latest.current) return
      setData(loaded)
      setLoadError(null)
    } catch (error) {
      if (ticket !== latest.current) return
      if (error instanceof ApiError && error.status === 404) {
        setLoadError(`The document is no longer available, so ${describe} could not be loaded.`)
      } else {
        console.error(`could not load ${describe}`, error)
        setLoadError(`Could not load ${describe}. Check your connection; the editor will retry automatically.`)
      }
    } finally {
      if (ticket === latest.current) setLoading(false)
    }
  }, [load, describe])

  const invalidate = useCallback(() => { latest.current++ }, [])
  const settledRevision = useDebouncedValue(revision)
  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'hidden') void refresh()
    }, 10_000)
    return () => { window.clearInterval(timer); invalidate() }
  }, [refresh, settledRevision, invalidate])

  return { data, loading, refresh, failure: { ...actions, message: actions.message ?? loadError, dismiss } }
}
