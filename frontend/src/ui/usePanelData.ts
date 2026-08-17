import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../api'
import { useDebouncedValue } from './useDebouncedValue'
import { useFailure, type Failure } from './useFailure'

export interface PanelData<T> {
  data: T
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
  const failure = useFailure()

  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const ticket = ++latest.current
    try {
      const loaded = await load()
      if (ticket !== latest.current) return
      setData(loaded)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return
      console.error(`could not load ${describe}`, error)
    }
  }, [load, describe])

  const settledRevision = useDebouncedValue(revision)
  useEffect(() => {
    void refresh()
  }, [refresh, settledRevision])

  return { data, refresh, failure }
}
