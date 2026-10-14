# Browser application

The editor uses React, TypeScript, and CodeMirror 6, with a TypeScript RGA CRDT that shares
conformance vectors with the Java implementation. It supports collaborative text and formatting,
remote cursors, role-based sharing, comments, suggestions, change history, and recoverable local
editing. The [root README](../README.md) covers the backend, architecture, and deployment.

## Run locally

Install dependencies with `npm ci` in this directory. Run the backend on port 8090 with Postgres
and Redis available, then run `npm run dev` to open the app on port 5173. Local name-only sign-in
requires `COLLABEDIT_DEV_LOGIN=true` on the backend; otherwise configure a supported sign-in
provider as described in the root README. The frontend does not start these services itself.

| Command | Purpose |
| --- | --- |
| `npm run dev` | Start the Vite development server. |
| `npm run typecheck` | Check application, tooling, and test TypeScript projects. |
| `npm run lint` | Run oxlint. |
| `npm test` | Run unit and conformance tests. |
| `npm run build` | Typecheck and produce `dist/`. |
| `npm run preview` | Serve an existing production build, normally on port 4173. |
| `npm run e2e` | Run browser workflows against already running services; excludes demo recordings. |
| `npm run demo` | Run and record both demonstration scenes. |

Install the Chromium test browser with `npx playwright install chromium` before the first browser
run. Playwright uses one worker and retains traces/videos for failed tests. `E2E_APP_URL` overrides
the browser application URL; `E2E_API_URL` overrides the backend origin used by test fixtures.
Set `E2E_PRODUCTION=true` when testing the production offline shell. These test settings do not
change the application's compiled API configuration.

## API and production configuration

`VITE_API_BASE` is a **build-time backend origin**, without `/api`; the client adds `/api` and
uses the same origin for `/ws`. When unset, port 5173 targets the same hostname on port 8090.
Other ports use the frontend origin. An explicitly empty value selects the frontend origin at
any port, which is appropriate behind the supplied Nginx proxy.

For a standalone preview talking directly to a local backend, build with
`VITE_API_BASE=http://localhost:8090 npm run build`, then run `npm run preview`. The backend's
allowed origins and sign-in return URL must also match the preview URL. Changing an environment
variable after building does not change the bundle.

The frontend container uses same-origin API/WebSocket requests. Its runtime `BACKEND_ORIGIN`
defaults to `http://app:8090`; change that when the backend has a different container name or
address. `VITE_OTLP_ENDPOINT` optionally enables browser tracing and is also set at build time.

## Editing and local recovery

Document and history panels are opened from the toolbar; they are not permanently open by
default. On narrow screens, document navigation and the side panel use drawers. Formatting buttons
show active and mixed selection states; links use an address editor. Select text to attach a
comment. Insertion suggestions use the current cursor or the end of the selection and have an
explicit submit button. Comment and reply bodies are limited to 4,000 UTF-16 units; insertion
suggestions are limited to 8,192.

Pending operations, comment queues, undo history, and cached document state are scoped by user
and document in IndexedDB. The document's comment outbox continues draining when its panel is
closed and waits for durable anchoring before removing a queued thread. Refused queued comments
retain a recovery copy. Unsubmitted panel drafts use tab-scoped browser storage, with an in-memory
fallback when that storage is blocked; the fallback cannot survive a page reload.

A production service worker caches public application assets so a previously visited app and
cached document can reopen offline. It does not cache authentication or document API responses.
Vite development builds do not install this offline shell. Offline editing of a cached replica
requires Web Locks: another tab holding the same editing identity makes the reopened copy
read-only. Browsers without Web Locks can still use a newly assigned online identity, but cached
offline copies remain read-only. The initial recovery copy must commit before editing is enabled, and acknowledged operations
remain buffered until the updated cache commits. Browser storage restrictions can prevent local
recovery and keep a newly opened document read-only.

The save indicator distinguishes connection and pending-save states. A recovered draft preserves
text and supported formatting, while comment and suggestion metadata remain attached to their
original document. Opening the recovered document lets the user inspect the saved copy.

## Source layout

```text
src/crdt/       RGA model, marks, codecs, snapshots, and undo history
src/transport/  STOMP, IndexedDB, comment delivery, writer leases, and network simulation
src/editor/     CodeMirror bridge, formatting, remote cursors, and document lifecycle
src/ui/         panels, draft/session storage helpers, and presentation
src/observability/ browser tracing
src/App.tsx     sign-in and workspace composition
public/sw.js    production public-asset cache
e2e/           browser workflows and recorded demonstrations
```

CodeMirror uses offsets locally; collaborative cursors, selections, comments, and formatting
resolve stable character identifiers. The browser model currently scans arrays where the server
uses a treap. Undo shares insertion/deletion chunk helpers with the editor bridge, so the directory
boundaries are not a strict one-way dependency graph. Large-document browser indexing and richer
inline comment/history workflows remain areas for improvement.

## Refresh the demonstrations

With the current frontend and a development-login backend running, execute `npm run demo`.
Recordings are written beneath `demo-recordings/core/` and `demo-recordings/product/`.
The core scene covers simultaneous editing, a network interruption, and history restoration;
the product scene covers formatting, comments, deletion, and restoration from the bin.

From the repository root, `tools/make-gif.sh core docs/demo.gif` and
`tools/make-gif.sh product docs/demo-v2.gif` convert recordings with ffmpeg and gifski.
By default the script selects the newest recording in the scene directory and prints its path.
For the exact clip from a successful current run, use
`DEMO_VIDEO=frontend/demo-recordings/core/<clip>.webm tools/make-gif.sh core docs/demo.gif`.
Select Anna's core recording to include the history controls. Do not use a failed-test recording
as evidence that a workflow completed.

The demos create fresh documents and do not require clearing a database. The administrative
`tools/reset-demo-data.sh` script is destructive: it targets the hardcoded `collabedit` database
in `collabedit-postgres` and removes documents and users, including dependent rows. It does not
follow an alternative application database URL. Stop the backend before an intentional reset,
and restart it afterward to discard its in-memory replicas. Never use that script to clean an
unrelated review or deployed database.
