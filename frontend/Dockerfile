# Frontend — build Vite, serwowany przez nginx.
#
# Zasada jak w app/Dockerfile i docker-compose.yml: żadnej wartości bez uzasadnienia.

# --- Etap 1: budowanie ------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /src

# package*.json przed resztą kodu — instalacja zależności trafia do własnej warstwy
# i nie powtarza się przy każdej zmianie w src/.
COPY package.json package-lock.json ./

# `npm ci`, nie `npm install`: instaluje DOKŁADNIE to, co w package-lock.json, i wywala się
# przy rozjeździe zamiast po cichu go naprawiać. Obraz ma być odtwarzalny.
RUN npm ci

COPY . .

# The backend address is injected at BUILD time, because Vite compiles environment variables
# into the bundle — they cannot be swapped when the container starts.
#
# The default is EMPTY, which means "same origin": nginx below proxies /api and /ws to the
# backend, so the browser only ever talks to one host. That is the shape a hosted deployment
# wants — one certificate, no CORS, and no `ws:` socket attempted from an `https:` page, which
# browsers block outright.
#
# It used to default to http://localhost:8080, which made the image work on a laptop and
# nowhere else. Set this only when the backend genuinely lives on a different origin from the
# frontend; otherwise leave it empty and let the proxy do its job.
ARG VITE_API_BASE=
ENV VITE_API_BASE=${VITE_API_BASE}

# Browser tracing is OFF unless a collector is named. Previously the exporter pointed at
# http://localhost:4318 unconditionally, so a hosted instance had every visitor's browser
# firing a blocked request once a second at a host that was theirs, not ours.
ARG VITE_OTLP_ENDPOINT=
ENV VITE_OTLP_ENDPOINT=${VITE_OTLP_ENDPOINT}

RUN npm run build

# --- Etap 2: serwowanie -----------------------------------------------------
#
# nginx zamiast `vite preview`: preview to serwer deweloperski, bez cache'owania,
# bez kompresji i bez gwarancji stabilności — nie ma powodu używać go jako produkcyjnego.
FROM nginx:1.27-alpine

COPY --from=build /src/dist /usr/share/nginx/html

# A template rather than a finished config: the nginx image expands ${VARIABLES} in
# /etc/nginx/templates/*.template at container start, so the backend upstream is a deployment
# setting instead of a rebuild. Only variables present in the environment are substituted,
# which is why BACKEND_ORIGIN is declared below.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Defaults to the compose service name. A deployment that runs the backend elsewhere overrides
# this one variable and changes nothing else.
ENV BACKEND_ORIGIN=http://app:8080

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --spider http://localhost/ || exit 1
