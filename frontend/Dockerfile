# 生产静态资源 + Nginx（API/WS 反代 backend:8080）
# 构建: docker compose --profile full build frontend
# 或:   docker build -t lianyu-pc/frontend:dev ./frontend

FROM node:20-alpine AS build
WORKDIR /build

ARG APP_REVISION=dev
ENV NODE_ENV=production

COPY package.json package-lock.json ./
RUN npm ci --silent

COPY index.html vite.config.js ./
COPY public public
COPY src src

RUN npm run build

FROM nginx:1.27-alpine
ARG APP_REVISION=dev
LABEL org.opencontainers.image.revision="${APP_REVISION}"

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
