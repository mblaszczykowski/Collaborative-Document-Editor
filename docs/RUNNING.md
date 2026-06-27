# Running it

Requirements: JDK 21, Node 22.12+, Docker.

```bash
docker compose --profile app up -d      # everything, on http://localhost:5173
```

The first run spends a few minutes building images.

## Working on the code

```bash
docker compose up -d postgres redis

# No default. The server refuses to start without it, because a secret committed here would let
# any reader mint a token for any user. Compose and CI set their own.
export COLLABEDIT_AUTH_SECRET=$(openssl rand -base64 32)

./mvnw -pl app spring-boot:run              # backend on :8090
cd frontend && npm install && npm run dev   # frontend on :5173
```

That gets you a running stack with **no way to sign in**, which is the intended default rather than
a failure: the login screen says so itself. Pick one of the two below.

**(a) Development login.** Type a name, become somebody. Nothing is checked. Off unless asked for,
per process, so no deployment gets it because somebody forgot a setting.

```bash
export COLLABEDIT_DEV_LOGIN=true
```

**(b) A real provider.** Create an OAuth client and register the redirect URI exactly, port
included:

| Provider | Console | Redirect URI |
|---|---|---|
| Google | console.cloud.google.com | `http://localhost:8090/login/oauth2/code/google` |
| GitHub | github.com/settings/developers | `http://localhost:8090/login/oauth2/code/github` |

```bash
export GOOGLE_CLIENT_ID=…  GOOGLE_CLIENT_SECRET=…
export GITHUB_CLIENT_ID=…  GITHUB_CLIENT_SECRET=…
```

That path is Spring Security's (`/login/oauth2/code/{registrationId}`), not this application's.
Registering the app's own URL instead is the usual first mistake and produces `redirect_uri_mismatch`
before any of our code runs.

`cp .env.example .env` and fill it in if you prefer a file; Compose reads it, and `.env` is
gitignored.

## Configuration

| Variable | Default | What it does |
|---|---|---|
| `COLLABEDIT_AUTH_SECRET` | *none — refuses to start* | Signs session tokens |
| `COLLABEDIT_DEV_LOGIN` | `false` | Serves `POST /api/login`, the name-only login |
| `GOOGLE_CLIENT_ID` / `_SECRET` | unset | Enables the Google button |
| `GITHUB_CLIENT_ID` / `_SECRET` | unset | Enables the GitHub button |
| `COLLABEDIT_APP_URL` | `http://localhost:5173` | Where a finished sign-in returns to |
| `COLLABEDIT_PORT` | `8090` | The API port; must match the registered redirect URI |

Both halves of a provider's credentials are required. With only the id set, the application would
start, show the button, and fail at the token exchange with `invalid_client`, so `configured()`
insists on the pair.

## Tests

```bash
./mvnw install                         # everything, integration tests on a real Postgres
./mvnw -pl crdt-core test              # property tests (1000 tries, random seed — it searches)
./mvnw -pl crdt-core -Pthorough test   # 10 000 tries, as in the nightly run
cd frontend && npm test                # client unit tests
cd frontend && npm run e2e             # two browsers, needs a running stack
```

A failing property prints its seed; `@Property(seed = "…")` replays it.

## Observability

```bash
docker compose --profile observability up -d   # Prometheus, Tempo, Grafana on :3001
```

Use a backend started from Maven on the host, not `--profile app`. The containerised backend sets
`OTEL_SDK_DISABLED` unconditionally, so a browser span arrives with no server span to join and the
trace looks broken rather than disabled.

## If you use colima rather than Docker Desktop

Testcontainers does not read the docker context, and Docker 29+ rejects the API version docker-java
negotiates by default. All three symptoms read as "no Docker":

```bash
# "Could not find a valid Docker environment"
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
# "error while creating mount source path ..." — the socket path inside the VM, for Ryuk
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
# "client version 1.32 is too old"
echo "api.version=1.44" > ~/.docker-java.properties
```

None of this is needed on GitHub runners.

## Deploying somewhere that is not a laptop

The frontend resolves the backend address at build time
([`frontend/src/config.ts`](../frontend/src/config.ts)) and defaults to the origin it was served
from, with nginx proxying `/api` and `/ws`. That is the only arrangement that works over TLS, since
a page served over `https:` may not open a `ws:` socket. For separate origins, set `VITE_API_BASE`
at build time and `COLLABEDIT_WEBSOCKET_ALLOWEDORIGINPATTERNS` on the backend.

## Regenerating the pictures in the README

Diagrams come from mermaid sources in [`diagrams/`](diagrams), the GIFs from a Playwright script.

```bash
./tools/render-diagrams.sh                 # docs/diagrams/*.mmd -> *.svg

./tools/reset-demo-data.sh
cd frontend && npm run demo
cd .. && ./tools/make-gif.sh core docs/demo.gif && ./tools/make-gif.sh product docs/demo-v2.gif
```
