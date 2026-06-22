import { useEffect, useState } from 'react'

/**
 * The interval between the last change and the value settling.
 *
 * 300 ms, chosen against typing rather than against a feel: a comfortable typing speed is 5–10
 * characters a second, so this collapses a run of keystrokes into one refresh while still landing
 * well inside the pause a person takes before looking at a panel.
 */
const PANEL_REFRESH_DEBOUNCE_MS = 300

/**
 * A value that stops changing while it is changing quickly.
 *
 * The three side panels refresh over REST when the document's revision changes, and the revision
 * is the character count — which moves on **every keystroke**. So typing a hundred characters
 * fired a hundred `GET /history` requests, and the history panel is the default one, so this was
 * the ordinary path rather than an edge case.
 *
 * `DocumentList` already debounced its search box for exactly this reason and wrote the argument
 * down ("every keystroke in the search box is a separate full-text query"); the panels were built
 * later and did not carry it over. This is that reasoning, extracted so all three share it.
 *
 * Note what this does NOT fix: overlapping responses arriving out of order. That needs a
 * per-effect guard, because only the effect knows which request its answer belongs to — see the
 * `stale` flag in the panels.
 */
export function useDebouncedValue<T>(value: T, delayMs = PANEL_REFRESH_DEBOUNCE_MS): T {
  const [settled, setSettled] = useState(value)

  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delayMs)
    return () => window.clearTimeout(timer)
  }, [value, delayMs])

  return settled
}
