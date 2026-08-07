#!/bin/sh
# nginx 启动前准备证书：
# - 若 compose 挂载了真实 certbot 证书（/etc/letsencrypt/live/gis.example.com/fullchain.pem 存在）则直接用；
# - 否则（本机无证书 / 未挂载）复制镜像内置自签占位证书兜底，保证容器能启动。
set -e

CERT_DIR=/etc/letsencrypt/live/gis.example.com
CERT_FILE=$CERT_DIR/fullchain.pem

if [ ! -f "$CERT_FILE" ]; then
  echo "[entrypoint] real certificate not found at $CERT_FILE, using self-signed placeholder"
  mkdir -p "$CERT_DIR"
  cp -f /etc/nginx/self-signed/fullchain.pem "$CERT_DIR/fullchain.pem"
  cp -f /etc/nginx/self-signed/privkey.pem "$CERT_DIR/privkey.pem"
fi

exec nginx -g "daemon off;"
