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

# Adres backendu jest wstrzykiwany na etapie BUDOWANIA, bo Vite wkompilowuje zmienne
# środowiskowe w bundle — nie da się ich podmienić przy starcie kontenera. Domyślna
# wartość odpowiada uruchomieniu przez docker-compose na localhoście.
ARG VITE_API_BASE=http://localhost:8080
ENV VITE_API_BASE=${VITE_API_BASE}

RUN npm run build

# --- Etap 2: serwowanie -----------------------------------------------------
#
# nginx zamiast `vite preview`: preview to serwer deweloperski, bez cache'owania,
# bez kompresji i bez gwarancji stabilności — nie ma powodu używać go jako produkcyjnego.
FROM nginx:1.27-alpine

COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --spider http://localhost/ || exit 1
