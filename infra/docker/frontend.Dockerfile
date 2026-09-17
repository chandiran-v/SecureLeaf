# ── Frontend Dockerfile ────────────────────────────────────────────────────
# Multi-stage build: compile with Node, serve with nginx

# Stage 1 — Build
FROM node:20-alpine AS builder

WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# Stage 2 — Serve with nginx
FROM nginx:1.27-alpine AS runtime

# Remove default nginx config; replace with SecureLeaf config
RUN rm /etc/nginx/conf.d/default.conf
COPY ../nginx/nginx.conf /etc/nginx/conf.d/secureleaf.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
