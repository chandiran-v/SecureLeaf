# syntax=docker/dockerfile:1
# Phase 09D D5 — production web image: Caddy + the BUILT React app. Build context: REPO ROOT
#   docker build -f infra/prod/caddy.Dockerfile .      (compose does this for you)
# Both stages are multi-arch (linux/amd64 + linux/arm64), so it builds on the Oracle Ampere box.

# Stage 1 — build the SPA
FROM node:20-alpine AS builder
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .

# Build-time variables (Vite bakes VITE_* into the JS). Never pass secrets here.
# VITE_API_URL / VITE_GRAPHQL_URL are deliberately NOT set: the app calls relative URLs.
ARG VITE_GOOGLE_CLIENT_ID=""
ARG VITE_SENTRY_DSN=""
ARG VITE_LEGAL_REVIEWED=""
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID \
    VITE_SENTRY_DSN=$VITE_SENTRY_DSN \
    VITE_LEGAL_REVIEWED=$VITE_LEGAL_REVIEWED
RUN npm run build

# Stage 2 — Caddy serves it
FROM caddy:2 AS runtime
COPY infra/prod/Caddyfile /etc/caddy/Caddyfile
RUN mkdir -p /etc/caddy/tls.d
COPY --from=builder /app/dist /srv
EXPOSE 80 443
