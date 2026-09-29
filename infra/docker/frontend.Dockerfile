# ── Frontend Dockerfile ────────────────────────────────────────────────────
# Multi-stage build: compile with Node, serve with nginx
#
# BUILD CONTEXT MUST BE THE REPO ROOT, not frontend/ — e.g.:
#   docker build -f infra/docker/frontend.Dockerfile -t secureleaf-frontend .
# This image needs two sibling directories (frontend/ for the app, infra/nginx/ for the nginx
# config it ships with) in one build, and Docker can never COPY a path from outside its build
# context — `COPY ../nginx/nginx.conf` (the pre-Phase-9 version of this file) looked fine but
# fails every time with "not found", because `frontend/` alone never gave Docker a way to see
# `infra/nginx/` at all. Building from the repo root, and prefixing every COPY with the
# subdirectory it actually comes from, is the standard fix for "one image needs files from two
# sibling folders" (see docs/deployment.md and the Phase 9 learning note).

# Stage 1 — Build
FROM node:20-alpine AS builder

WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ .
RUN npm run build

# Stage 2 — Serve with nginx
FROM nginx:1.27-alpine AS runtime

# Remove default nginx config; replace with SecureLeaf config
RUN rm /etc/nginx/conf.d/default.conf
COPY infra/nginx/nginx.conf /etc/nginx/conf.d/secureleaf.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
