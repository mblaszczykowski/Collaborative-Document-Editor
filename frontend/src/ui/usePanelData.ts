import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../api'
import { useDebouncedValue } from './useDebouncedValue'
import { useFailure, type Failure } from './useFailure'

/**
 * What the three side panels do identically, in one place.
 *
 * ## What was actually wrong, rather than "there was duplication"
 *
 * All three panels reload over REST when the document's revision changes, and the revision is the
 * character count — so it moves on every keystroke. Four rules follow from that, and the three
 * copies of them had drifted:
 *
 * 1. **debounce**, or it is one request per character. All three had this, through
 *    `useDebouncedValue`;
 * 2. **a stale guard**, because debouncing does not order responses — `useDebouncedValue`'s own
 *    documentation says so and names this as the thing it does not cover. `HistoryPanel` and
 *    `DocumentList` had it. `CommentsPanel` and `SuggestionsPanel` did NOT, so a slow answer for
 *    revision N landing after the answer for N+1 left a list that does not match the document,
 *    with nothing on screen to say so;
 * 3. **swallowing a 404**, because the panels stay mounted while the document is being deleted or
 *    access is revoked, and the server answers 404 for all three of "deleted", "never existed" and
 *    "not yours". The workspace says that once, in its own banner; a red console line per panel
 *    for the ordinary way a panel ends its life is noise;
 * 4. **a failure state for the panel's own GESTURES**, which is a different thing from the
 *    background reload — see below.
 *
 * ## Why the background reload never produces a message
 *
 * It runs on its own, roughly every time typing settles. A banner about it would appear without
 * anybody having asked for anything, and would come back on the next keystroke. The console keeps
 * the detail and the panel keeps showing the list it already has — which is better than emptying
 * itself and implying the conversations, versions or proposals are gone. `HistoryPanel` argued
 * this in a comment and was the only one of the three doing it.
 *
 * ## What this deliberately is NOT
 *
 * A data-fetching abstraction. There is no cache, no retry, no deduplication and no shared store.
 * It also does not absorb `DocumentList`, which debounces a QUERY STRING on its own interval and
 * distinguishes "no search" from "a search with no matches" — pulling it in would mean options for
 * a case none of the three panels has, and the extraction would then be paying for itself in
 * configuration.
 */
export interface PanelData<T> {
  data: T
  /**
   * Fetches now, without waiting for the debounce — for a gesture that has just changed the data.
   *
   * It reports nothing to the user, on purpose. The gesture already has its own message through
   * `failure`, and a refresh that fails AFTER the action succeeded must not be reported as the
   * action failing: `SuggestionsPanel` had its reload inside the try, so accepting a proposal and
   * then failing to re-list told the user "that text is no longer there" about a change that had
   * gone through.
   */
  refresh: () => Promise<void>
  /** For the panel's own gestures. The background reload never touches it — see above. */
  failure: Failure
}

export function usePanelData<T>({ load, revision, initial, describe }: {
  load: () => Promise<T>
  /** Bumped whenever the document changes; debounced here. */
  revision: number
  initial: T
  /** Names the data in the console when a background reload fails. */
  describe: string
}): PanelData<T> {
  const [data, setData] = useState<T>(initial)
  const failure = useFailure()

  /**
   * The number of the most recent request, which is how an answer learns it is stale.
   *
   * A counter rather than the `let stale = false` an effect cleanup can flip, because `refresh` is
   * also called from gestures that have no cleanup to hook into — and a gesture's refresh has to
   * cancel an automatic one that is still in flight just as surely as the other way round.
   */
  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const ticket = ++latest.current
    try {
      const loaded = await load()
      // A newer request went out while this one was travelling. Its answer is the one that matches
      // what the user is looking at, so this one is dropped rather than drawn.
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
