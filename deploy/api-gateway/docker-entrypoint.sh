#!/bin/sh
set -eu
/generate-cors-map.sh
# ops dashboard 上游地址由 .env 注入（默认 docker 网桥宿主侧 + 8090），避免硬编码进镜像
sed -i "s|__OPS_DASHBOARD_UPSTREAM__|${OPS_DASHBOARD_UPSTREAM:-http://172.18.0.1:8090}|g" /etc/nginx/conf.d/default.conf
exec nginx -g 'daemon off;'
