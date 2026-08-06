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
# 证书占位目录（生产用 compose-prod.yaml 挂载真实证书）
RUN mkdir -p /etc/nginx/certs
EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]