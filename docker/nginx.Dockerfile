# 前端静态站点 + Nginx 反代镜像。
# 1) 构建前端（Vite 已通过） -> 产物拷贝到 Nginx web 根
# 2) 拷贝 Nginx 配置
# 国内网络拉 Docker Hub 不通时，可用加速器前缀：
#   docker compose -f compose-prod.yaml build --build-arg NODE_IMAGE=docker.m.daocloud.io/library/node:20-alpine --build-arg NGINX_IMAGE=docker.m.daocloud.io/library/nginx:1.27-alpine
ARG NODE_IMAGE=node:20-alpine
ARG NGINX_IMAGE=nginx:1.27-alpine
# npm registry：国内拉 npmjs 不通时用 npmmirror；留空则用 npm 默认官方源。
ARG NPM_REGISTRY=https://registry.npmmirror.com
FROM ${NODE_IMAGE} AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN if [ -n "${NPM_REGISTRY}" ]; then npm config set registry "${NPM_REGISTRY}"; fi \
    && npm ci --no-audit --no-fund
COPY frontend ./
# 容器内默认 JS heap 偏小，vite 打包容易 OOM，显式放大
ENV NODE_OPTIONS=--max-old-space-size=4096
RUN npm run build

FROM ${NGINX_IMAGE}
# 轻量健康检查映像检查（busybox wget）
RUN rm -f /usr/share/nginx/html/*.html
COPY --from=frontend-build /frontend/dist /usr/share/nginx/html
COPY docker/nginx/nginx.conf /etc/nginx/conf.d/default.conf
# 生成自签占位证书：无真实证书时 nginx 也能启动（HTTPS 会提示不受信）。
# 生产用 compose-prod.yaml 挂载 certbot 真实证书到 /etc/letsencrypt，entrypoint
# 检测到真实证书存在则使用之，否则复制占位证书兜底。
# 部署时若使用其它域名，请同步修改 nginx.conf 中的证书路径。
RUN apk add --no-cache openssl \
    && mkdir -p /etc/nginx/self-signed \
    && openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
        -keyout /etc/nginx/self-signed/privkey.pem \
        -out /etc/nginx/self-signed/fullchain.pem \
        -subj "/CN=localhost"
# 启动前：若 /etc/letsencrypt 下无真实证书（宿主未挂载），用自签占位兜底
COPY docker/nginx/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 80 443
CMD ["/entrypoint.sh"]