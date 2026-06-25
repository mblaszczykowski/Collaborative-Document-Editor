import { useCallback, useState } from 'react'

/**
 * One place for "this did not work, and the user has to be told".
 *
 * ## What this replaces
 *
 * Most of the asynchronous gestures in the interface were written as `void something()` or
 * `onClick={handler}` on an `async function` with no `catch`. The consequence is the same
 * everywhere and it is the worst kind of bug this product can have: the request fails, the promise
 * rejects into nothing, the button re-enables, and the user is left looking at a screen that says
 * their work went through. Renaming a document, creating one, revoking an invitation, restoring a
 * version, moving a document to the bin — every one of them failed silently.
 *
 * The project's own standard was already the opposite in two places written later:
 * `SuggestionsPanel.run` catches and names the failure, and `useCollaborativeDocument`'s
 * `USER_FACING_ERRORS` decides deliberately which codes are worth interrupting somebody for. This
 * hook is that standard, extracted so the other panels can hold it too rather than each growing
 * its own copy.
 *
 * ## Why a hook and not a framework
 *
 * It does three things and refuses the fourth. It runs the action, it keeps ONE message, and it
 * logs the real error to the console for whoever is debugging. It does not retry, it does not
 * classify errors, and it does not render anything — retrying is a decision only the call site can
 * make (posting a comment twice is not the same as renaming a document twice), and rendering
 * belongs where the layout is. `FailureNote` is a separate file for the same reason.
 *
 * The message is written by the CALLER, in the product's language, because only the caller knows
 * what the user was trying to do. Passing the exception's own words through would put a stack
 * trace's vocabulary on screen — the mistake `USER_FACING_ERRORS` documents at length.
 */
/**
 * Exported because it is the return type of the exported hook, and callers that hold a failure
 * without calling the hook themselves need to name it — `usePanelData` passes one through.
 */
export interface Failure {
  /** The last failure worth showing, or null. */
  message: string | null
  dismiss: () => void
  /**
   * Says a failure happened, for the callers that are not wrapping one awaited action.
   *
   * `attempt` covers a gesture; this covers the cases where the failure is decided somewhere else.
   * `CommentsPanel` has three of them and had to hand-roll its own state for all three: draining
   * the outbox refuses ONE comment out of many inside a loop and carries on, and the storage
   * failures it reports happen in a `catch` that also has work to do (putting the user's text back
   * in the box). Neither is "run this and tell me if it worked".
   */
  report: (message: string) => void
  /**
   * Runs an action, reporting a failure rather than dropping it.
   *
   * Returns whether it succeeded, so a caller that has to clear a draft or close a dialog can do
   * it on the success path only — `SuggestionsPanel` cleared its draft in a `.then` after a
   * swallowed error, which lost the user's text AND told them it had failed.
   */
  attempt: (message: string, action: () => Promise<unknown>) => Promise<boolean>
}

export function useFailure(): Failure {
  const [message, setMessage] = useState<string | null>(null)
  const dismiss = useCallback(() => setMessage(null), [])
  const report = useCallback((text: string) => setMessage(text), [])

  const attempt = useCallback(async (text: string, action: () => Promise<unknown>) => {
    // Cleared first: leaving the previous message up while a retry is in flight means a successful
    // retry ends with the old failure still on screen, which is the same lie in the other
    // direction.
    setMessage(null)
    try {
      await action()
      return true
    } catch (error) {
      // The console keeps the real thing — status, stack, the server's own words — because that is
      // what a developer needs and none of it belongs on screen. Same split as `onError` in
      // `useCollaborativeDocument`.
      console.error(text, error)
      setMessage(text)
      return false
    }
  }, [])

  return { message, dismiss, report, attempt }
}
