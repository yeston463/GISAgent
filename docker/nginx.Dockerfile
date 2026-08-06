# 前端静态站点 + Nginx 反代镜像。
# 1) 构建前端（Vite 已通过） -> 产物拷贝到 Nginx web 根
# 2) 拷贝 Nginx 配置
FROM node:20-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund
COPY frontend ./
RUN npm run build

FROM nginx:1.27-alpine
# 轻量健康检查映像检查（busybox wget）
RUN rm -f /usr/share/nginx/html/*.html
COPY --from=frontend-build /frontend/dist /usr/share/nginx/html
COPY docker/nginx/nginx.conf /etc/nginx/conf.d/default.conf
# 生成自签占位证书：无真实证书时 nginx 也能启动（HTTPS 会提示不受信）；
# 生产用 compose-prod.yaml 挂载 certbot 真实证书到 /etc/letsencrypt 自动覆盖。
# 部署时若使用其它域名，请同步修改本行域名与 nginx.conf 中的路径。
RUN apk add --no-cache openssl \
    && mkdir -p /etc/letsencrypt/live/gis.example.com \
    && openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
        -keyout /etc/letsencrypt/live/gis.example.com/privkey.pem \
        -out /etc/letsencrypt/live/gis.example.com/fullchain.pem \
        -subj "/CN=gis.example.com" \
    && rm -rf /etc/nginx/certs
EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]