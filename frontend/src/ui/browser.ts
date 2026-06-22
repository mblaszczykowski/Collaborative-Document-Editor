/**
 * The two browser APIs this application uses that **do not exist outside a secure context**.
 *
 * ## Why this file exists at all
 *
 * `crypto.randomUUID` and `navigator.clipboard` are only defined on a page served from `https:` or
 * from `localhost`. Everywhere else `crypto.randomUUID` is `undefined` — so calling it throws a
 * `TypeError` — and `navigator.clipboard` is `undefined` in Chromium and present-but-rejecting in
 * some other browsers.
 *
 * That is not a hypothetical deployment for this project. `config.ts` deliberately supports the
 * `http:` → `ws:` shape and documents it as one of the three arrangements the app is built for, so
 * `http://<lan-ip>:5173` — the obvious way to show two browsers editing one document, which is
 * the demo this whole product is — is a supported deployment where both of these are missing.
 *
 * What that cost before this file:
 *
 * - **commenting was dead.** `CommentsPanel.open` called `crypto.randomUUID()` on the line before
 *   its `try`, so the `TypeError` escaped the whole error path. The draft had already been
 *   cleared, `void open()` rejected into nothing, and the comment was gone with no message, no
 *   queue entry and nothing in the interface to suggest anything had happened. The one path in
 *   that file whose entire purpose is not losing the comment;
 * - **sharing was silent.** `navigator.clipboard.writeText` on `undefined` threw the same way, and
 *   the Share button reported "Copied" or nothing at all depending on where the throw landed.
 *
 * ## The rule these two follow
 *
 * Neither function throws. A caller that cannot have an identifier cannot do its job, so
 * `clientId` always returns one; a caller that could not reach the clipboard has to offer the user
 * another route, so `copyText` returns whether it worked instead of failing.
 */

/**
 * An identifier for one attempt at posting a comment (CP27).
 *
 * It is the exactly-once key: the same value travels with every retry of the same comment, and the
 * server turns a repeated key into a no-op. So what it has to be is **unique among the attempts a
 * server can see**, which is a much weaker requirement than a v4 UUID's — but weak is not the same
 * as careless, because a collision here means one user's comment silently swallowing another's.
 *
 * `crypto.randomUUID` when it exists. When it does not, `crypto.getRandomValues` — which is
 * available on `http:` as well, being part of the original Web Crypto surface rather than the
 * secure-context additions — formatted into the same v4 shape so the server sees one format. Only
 * if BOTH are missing (no `crypto` object at all) does this fall back to `Math.random`, which is
 * not a cryptographic source and is documented here as the last resort it is: a browser that old
 * has no offline comment queue to protect anyway, since it predates the rest of what this app
 * needs.
 */
export function clientId(): string {
  const source = globalThis.crypto as Crypto | undefined
  if (typeof source?.randomUUID === 'function') {
    return source.randomUUID()
  }
  const bytes = new Uint8Array(16)
  if (typeof source?.getRandomValues === 'function') {
    source.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index++) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  // The version and variant bits, so the value is a well-formed v4 rather than sixteen random
  // bytes wearing a UUID's punctuation. The server stores it as a string and does not parse it,
  // but a value that claims a format should hold it — the next reader will assume it does.
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * Puts text on the clipboard, and says whether it got there.
 *
 * Returns `false` rather than throwing, because there is always something better for the caller to
 * do than nothing: both call sites answer a `false` by putting the text on screen in a field the
 * user can select and copy by hand. That is a worse gesture than a working button and a far better
 * one than a button that lies.
 *
 * No `document.execCommand('copy')` fallback. It is deprecated, it needs a hidden element and a
 * live selection, and on the deployment shape that actually loses the clipboard — a plain-`http:`
 * page — Chromium blocks it for the same reason it blocks the modern API. A fallback that does not
 * work in the case it exists for is worse than admitting the failure.
 */
export async function copyText(text: string): Promise<boolean> {
  const clipboard = globalThis.navigator?.clipboard
  if (clipboard === undefined) return false
  try {
    await clipboard.writeText(text)
    return true
  } catch {
    // Present but refused: no permission, or the call did not come from a user gesture the
    // browser recognises. Same answer as absent — the caller has to show the text either way.
    return false
  }
}
