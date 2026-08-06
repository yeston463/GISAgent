# 生产部署手册（预发布 / Docker Compose）

> 目标拓扑：**只允许 Nginx 暴露 80/443**，Java / Python GIS / Redis / PostgreSQL 全部在
> Docker 内部网络 `internal` 中，公网无法直接触达。CityEngine / GeoScene 在宿主机原生运行，
> python-gis 通过 `host.docker.internal` 反向访问。

```
公网 --HTTPS--> Nginx(80/443) --/api--> backend(Java:8080)
                        |           \--/analysis--> backend 代理 --> python-gis(FastAPI:8000)
                        |                            （python 永不直接暴露）
                        `--/-----> 前端静态站点（Vue 构建产物）
backend --internal--> redis / pgvector（无端口映射）
python-gis --host.docker.internal--> 宿主机 CityEngine / GeoScene
```

---

## 0. 交付物清单

| 文件 | 作用 |
|------|------|
| `compose-prod.yaml` | 生产编排（唯一暴露 80/443） |
| `docker/backend.Dockerfile` | Java 后端镜像（Maven 构建 → JRE17，非 root） |
| `docker/gis.Dockerfile` | Python GIS 镜像（FastAPI，非 root） |
| `docker/nginx.Dockerfile` | 前端构建 → Nginx 镜像 |
| `docker/nginx/nginx.conf` | TLS / 反代 / 安全头 / 隐藏运维端点 |
| `.env.production.example` | 生产环境变量模板（复制为 `.env` 后填值） |
| `src/main/resources/application-prod.yml` | Spring `prod` profile 配置 |

---

## 1. 服务器前置条件

- 云服务器（公网 IP）+ 域名，域名已解析到该 IP；
- 中国大陆服务器需完成 **ICP 备案**（未备案会拦截 80/443）；
- 安装 Docker + Docker Compose plugin（`docker compose version`）；
- 服务器内存建议 ≥ 8GB（Java + Postgres + Redis + Nginx）。

---

## 2. 准备生产密钥（每个都要独立强随机）

```bash
# 在服务器上生成（PowerShell 也可用 openssl）
openssl rand -base64 48   # AUTH_JWT_SECRET
openssl rand -base64 32   # POSTGRES_PASSWORD
openssl rand -base64 32   # REDIS_PASSWORD
openssl rand -base64 32   # AUTH_ADMIN_PASSWORD
```

> **上线前必须重置开发环境用过的所有密钥**：`.env` 中的 GeoScene 门户口令、
> DeepSeek / Qwen API Key 一律轮换为生产专用值，绝不复用。

---

## 3. 申请 HTTPS 证书

```bash
# 方式一：Let's Encrypt（certbot）
apt install -y certbot
certbot certonly --standalone -d gis.example.com --non-interactive \
  --agree-tos -m you@example.com

# 证书位于：/etc/letsencrypt/live/gis.example.com/{fullchain.pem,privkey.pem}
```

Nginx 容器通过 `compose-prod.yaml` 里的卷挂载读取：
`/etc/letsencrypt:/etc/letsencrypt:ro`。若用其它证书，请同步修改
`docker/nginx/nginx.conf` 中的 `ssl_certificate` 路径，并挂载对应目录。

> 证书有效期约 90 天，配置 crontab 自动续期：
> `0 3 * * 1 certbot renew --quiet --deploy-hook 'docker compose -f compose-prod.yaml restart nginx'`

---

## 4. 部署步骤

```bash
# 在服务器上拉取代码后
cp .env.production.example .env
# 编辑 .env，填入全部密钥（见上一步）

# 编辑 nginx.conf 域名与证书路径（把 server_name、证书路径改为正式域名）
vim docker/nginx/nginx.conf

# 构建并启动
docker compose -f compose-prod.yaml build
docker compose -f compose-prod.yaml up -d

# 观察启动
docker compose -f compose-prod.yaml ps
docker compose -f compose-prod.yaml logs -f backend
```

---

## 5. 首次启动引导

1. 等 `backend` healthy（healthcheck 命中 `/actuator/health`）；
2. 用 `.env` 里 `AUTH_ADMIN_USERNAME/AUTH_ADMIN_PASSWORD` 登录（引导管理员，
   不落库，来源标记为 `bootstrap`）；
3. 登录后 `POST /api/auth/users`（admin）创建正式管理员 / 普通用户；
4. 正式用户落库到 `app_users`，之后即可正常管理。

> 引导管理员密码由环境变量提供，随时可改；数据库可用前鉴权链路自动降级，
> 不会因 DB 未就绪而拒绝启动。

---

## 6. 端口与网络对照

| 服务 | 容器端口 | 宿主端口 | 可达性 |
|------|---------|---------|--------|
| nginx | 80/443 | 80/443 | 公网 |
| backend | 8080 | 无 | 仅 internal（nginx 代理） |
| python-gis | 8000 | 无 | 仅 internal（backend 代理） |
| redis | 6379 | 无 | 仅 internal |
| pgvector | 5432 | 无 | 仅 internal |
| CityEngine / GeoScene | 宿主机 | 宿主机 | 仅宿主 + host.docker.internal |

安全收益：公网扫描不到 5432/6379/8080/8000；DB 口令不再默认 `postgres/postgres`；
`/analysis/**` 需登录且经 backend 转发，Python 服务不暴露。

---

## 7. 上线巡检清单

- [ ] `.env` 已填全部密钥且是**生产独立**随机值（不复用开发密钥）
- [ ] 域名已备案、证书生效、80 自动 301 到 443
- [ ] `docker compose -f compose-prod.yaml ps` 全部 healthy
- [ ] 未登录访问 `https://<域名>/api/gis/context` 返回 401
- [ ] 错误密码登录返回 401；正确登录拿到 JWT
- [ ] 普通用户访问 `/api/knowledge/reload` 返回 403
- [ ] `curl https://<域名>/analysis/runtime`（带 token）返回 python 运行信息
- [ ] 直接访问 `:5432` / `:6379` / `:8080` / `:8000` 均超时或拒绝
- [ ] 上传超大文件被 `client_max_body_size`（130m）拦截
- [ ] 伪造后缀（文本改名 `.pdf`）上传返回 413 `pdf_magic_mismatch`
- [ ] 短时间高频请求触发 429 + `Retry-After`（限流生效）
- [ ] 无令牌访问 `/analysis/runtime` 返回 401（Python 不直接暴露）
- [ ] `/actuator/*` 外部访问被 Nginx 拒绝（403）
- [ ] 验证 GeoScene / CityEngine 经 `host.docker.internal` 可达
- [ ] 日志中无明文密码、无 LLM debug 输出
- [ ] 运行 `scripts/verify-security-hardening.ps1` 输出 `10 passed, 0 failed`

---

## 8. 运维常用命令

```bash
# 滚动更新（改完代码后重建 backend/python-gis）
docker compose -f compose-prod.yaml build backend python-gis
docker compose -f compose-prod.yaml up -d backend python-gis

# 查看日志
docker compose -f compose-prod.yaml logs -f --tail=100 backend

# 备份 pgvector 数据卷
docker run --rm -v lc4j-prod_pgvector-data:/data -v $(pwd):/backup \
  alpine tar czf /backup/pgvector-$(date +%F).tgz -C /data .

# 清理
docker compose -f compose-prod.yaml down
```

---

## 9. 已知边界 / 尚未落地

- **上传安全**：已实现深格式校验（魔数）、可选 ClamAV 扫描、每用户/全局配额、
  TTL 过期清理（`UPLOAD_*` 环境变量）。尚未落地对象存储与图片缩略图。
- **限流**：已实现认证用户/IP 全局限流（`AUTH_RATE_LIMIT_*`）+ 登录暴力破解锁定，
  429 响应带 `Retry-After`。
- **Python 异常脱敏**：已实现 `_server_error` 统一脱敏，客户端仅收通用提示，
  完整 traceback 仅落服务器日志。
- **CityEngine / GeoScene**：宿主服务与容器之间的目录共享依赖 bind mount，
  路径需按服务器实际布局调整；
- 本地 Windows 开发机无 Docker daemon，以上镜像未在本机实际构建验证；
  首次上云前请在服务器执行一次 `docker compose -f compose-prod.yaml build` 排障。

---

## 10. 验收脚本（本地联调 / 上线巡检可复用）

后端（Java:8080）与 Python GIS（:8000）启动后，在开发机或服务器运行：

```powershell
# 从 .env 读取 AUTH_ADMIN_PASSWORD，全链路验收
powershell -ExecutionPolicy Bypass -File scripts/verify-security-hardening.ps1

# 指定密码 / 地址 / 附加限流 429 探测
.\scripts\verify-security-hardening.bat -AdminPassword xxx -TestRateLimit
```

覆盖：401（无令牌）、错误密码登录、JWT 登录与 `/me`、RBAC（`/knowledge/reload`
无令牌 401 / admin 200）、上传深检（伪造 PDF → 413 `pdf_magic_mismatch`、
合法 `.md` → 200）、`/analysis/runtime` 代理（无令牌 401 / 带令牌 200）。
默认全部通过输出 `10 passed, 0 failed`。
