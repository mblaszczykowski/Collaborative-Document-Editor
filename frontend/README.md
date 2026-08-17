# frontend

The browser half of the editor: React + TypeScript, CodeMirror 6 as the view, and **a second
implementation of the CRDT** in `src/crdt/`.

The project's own documentation lives at the repository root: [`../README.md`](../README.md)
covers what this is, how the algorithms work and how it performs. This file covers only what is
specific to running and reading this directory.

## Layout

```
src/crdt/       the RGA implementation. No React, no CodeMirror, no network
src/transport/  the STOMP connection, the offline buffer (IndexedDB), the network simulator
src/editor/     the bridge between the CRDT and CodeMirror; cursors, marks, undo wiring
src/ui/         panels and presentation; nothing here knows the merge algorithm
e2e/            Playwright specs, run against a real backend (see below)
```

The layering is one-directional and deliberate: `crdt/` imports nothing from the other three, and
`transport/` imports nothing from `editor/` or `ui/`. `src/crdt/` is the twin of `crdt-core/` in
Java, and the two are held together by the shared vectors in [`../conformance/`](../conformance/).

## Commands

```bash
npm run dev        # Vite dev server on :5173, needs the backend on :8090
npm run typecheck  # tsc over app, node and test projects
npm run lint       # oxlint
npm run test       # vitest: the CRDT, the codec, the conformance corpus, undo
npm run build      # typecheck, then a production bundle
npm run e2e        # Playwright against a running backend and frontend
npm run demo       # the two scenes tools/make-gif.sh turns into the README's GIF
```

`e2e` and `demo` do **not** start anything. The backend needs Postgres and Redis, so bringing it up
belongs in `docker compose` and in CI as visible steps rather than hidden in
`playwright.config.ts`.

## Two things worth knowing before reading the code

**Positions are character identifiers, not offsets.** Cursors, comment anchors, formatting ranges
and suggestion anchors all name a character rather than a place, because an offset is stale the
moment somebody types before it. The root README works through why.

**The index here is naive on purpose.** `src/crdt/document.ts` scans a list where the server uses a
treap. The server's version was replaced only after a benchmark showed the scan hurt, and the
equivalent measurement in a browser does not exist yet, so it is not optimised blind.
