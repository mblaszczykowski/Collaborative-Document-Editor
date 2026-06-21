# Frontend — a Vite build, served by nginx.
#
# The same principle as in app/Dockerfile and docker-compose.yml: no value without a reason.

# --- Stage 1: build ---------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /src

# package*.json before the rest of the code — installing dependencies gets its own layer and does
# not repeat on every change under src/.
COPY package.json package-lock.json ./

# `npm ci`, not `npm install`: it installs EXACTLY what package-lock.json says and fails on a
# mismatch instead of silently fixing it. The image is meant to be reproducible.
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

# --- Stage 2: serving -------------------------------------------------------
#
# nginx rather than `vite preview`: preview is a development server, with no caching, no
# compression and no stability guarantees — there is no reason to use it in production.
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
