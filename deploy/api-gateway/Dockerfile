# API-only TLS gateway (no frontend static assets)
FROM nginx:1.27-alpine

ARG APP_REVISION=dev
LABEL org.opencontainers.image.revision="${APP_REVISION}"

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY generate-cors-map.sh /generate-cors-map.sh
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /generate-cors-map.sh /docker-entrypoint.sh

EXPOSE 80 443
ENTRYPOINT ["/docker-entrypoint.sh"]
