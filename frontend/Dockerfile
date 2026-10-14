# --- Stage 1: build ---------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /src

COPY package.json package-lock.json ./

RUN npm ci

COPY . .

ARG VITE_API_BASE=
ENV VITE_API_BASE=${VITE_API_BASE}

ARG VITE_OTLP_ENDPOINT=
ENV VITE_OTLP_ENDPOINT=${VITE_OTLP_ENDPOINT}

RUN npm run build

FROM nginx:1.27-alpine

COPY --from=build /src/dist /usr/share/nginx/html

COPY nginx.conf.template /etc/nginx/templates/default.conf.template

ENV BACKEND_ORIGIN=http://app:8090

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1/ || exit 1
