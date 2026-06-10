# API-only TLS gateway (no frontend static assets)
FROM nginx:1.27-alpine

ARG APP_REVISION=dev
LABEL org.opencontainers.image.revision="${APP_REVISION}"

COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80 443
