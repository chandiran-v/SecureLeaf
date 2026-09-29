# syntax=docker/dockerfile:1
# ── Frontend Dockerfile (LOCAL / CI use — production uses infra/prod/caddy.Dockerfile) ─────
# Multi-stage build: compile with Node, serve with nginx.
#
# BUILD CONTEXT IS frontend/ :   docker build -f infra/docker/frontend.Dockerfile frontend/
# That is what CI does. The pre-Phase-09D file copied ../nginx/nginx.conf, a path OUTSIDE the
# build context, which Docker can never see — so the image could not build. Phase 09D D5 fixes it
# by making the file self-contained: the nginx config is inlined below with a BuildKit heredoc,
# so nothing outside frontend/ is needed. (infra/nginx/nginx.conf was removed — one source only.)

# Stage 1 — Build
FROM node:20-alpine AS builder

WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# Stage 2 — Serve with nginx
FROM nginx:1.27-alpine AS runtime

# Replace the default nginx config with SecureLeaf's
RUN rm /etc/nginx/conf.d/default.conf
COPY <<'NGINX' /etc/nginx/conf.d/secureleaf.conf
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # ── SPA routing — all unknown paths fall back to index.html ─────────────
    location / {
        try_files $uri $uri/ /index.html;
    }

    # ── Proxy GraphQL + REST API to backend ─────────────────────────────────
    location /graphql {
        proxy_pass         http://backend:8080/graphql;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /api/ {
        proxy_pass         http://backend:8080/api/;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # ── Security headers ─────────────────────────────────────────────────────
    add_header X-Frame-Options "DENY";
    add_header X-Content-Type-Options "nosniff";
    add_header Referrer-Policy "strict-origin-when-cross-origin";

    # ── Disable caching for index.html (cache busted by Vite hashes) ─────────
    location = /index.html {
        add_header Cache-Control "no-store, no-cache, must-revalidate";
    }

    # ── Cache static assets aggressively ─────────────────────────────────────
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff2)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
NGINX

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
