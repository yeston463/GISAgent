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
`/etc/letsencrypt:/etc/letsencrypt`。若用其它证书，请同步修改
`docker/nginx/nginx.conf` 中的 `ssl_certificate` 路径，并挂载对应目录。

> **无证书也能启动**：镜像内置自签占位证书。`docker/nginx/entrypoint.sh` 启动时
> 检测 `/etc/letsencrypt/live/gis.example.com/fullchain.pem` 是否存在——不存在则
> 自动复制自签证书兜底（HTTPS 可访问但浏览器提示不受信）。挂载需**可写**（非 `:ro`），
> 否则兜底写入失败。申请到正式证书后无需改动，自动使用真实证书。

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

### 4.1 国内网络构建加速（拉 Docker Hub / npm 不通时）

- **镜像加速**：在 `.env` 里把 `NODE_IMAGE` / `NGINX_IMAGE` / `MAVEN_IMAGE` /
  `JRE_IMAGE` / `PYTHON_IMAGE` 指到可达加速器前缀，例如
  `JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre`。
  Dockerfile 用 `ARG` + `FROM ${IMAGE}` 支持运行时注入（见各 Dockerfile 顶部注释）。
- **Maven 依赖**：在 `.env` 设 `MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public`，
  Dockerfile 会自动生成 settings.xml 使用该镜像，并把 `/root/.m2` 挂为 BuildKit 缓存
  （重复构建不重下依赖）。
- **npm 依赖**：`NPM_REGISTRY` 默认 `https://registry.npmmirror.com`，可改回官方源。
- **走代理构建**（国内服务器 / 开发机挂代理时）：Docker Desktop 的 `docker pull` 会自动
  用引擎代理，但 **BuildKit 构建仍需显式环境变量**，否则拉 registry token 会直连被墙 IP：

  ```bash
  export HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897
  docker compose -f compose-prod.yaml build
  ```

- **本机已验证**：backend / nginx / python-gis 三个镜像在 Docker Desktop 上全部构建 +
  运行通过；`docker compose up` 全栈 healthy；验收脚本 10/10（见第 11 节）。

---

## 5. 首次启动引导

1. 等 `backend` healthy（healthcheck 命中 `/actuator/health`，backend 镜像内置 curl 探测）
   与 `python-gis` healthy（`/health` 端点，见 `gis/router.py`）；
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

# 备份（完整：pg_dump + Redis RDB + 上传目录，见第 9.5 节）
bash scripts/backup-prod.sh ./backups 7

# 清理
docker compose -f compose-prod.yaml down
```

---

## 9. 上线前硬条件 · 逐项准备

> 以下每项对应一个"能否上线"的硬门槛，按顺序逐项完成。

### 9.1 独立生产密钥（模板默认留空）

```powershell
# 服务器/开发机执行：自动生成 JWT/PG/Redis/管理员密码并写入 .env
cp .env.production.example .env
powershell -ExecutionPolicy Bypass -File scripts/gen-prod-secrets.ps1
```

脚本会写 JWT 密钥 + PG/Redis/管理员密码（URL-safe 强随机），并提示仍需**人工填写**
的生产专用值：`GEOSCENE_PORTAL_PASSWORD`、`QWEN-APIKEY`、`DEEPSEEK_API_KEY`、`AMAP_KEY`
（绝不复用开发环境的 Key / 账号密码）。

### 9.2 域名与证书

```powershell
# 把占位域名换成正式域名（同步改 nginx.conf + entrypoint.sh，保留 .bak）
powershell -ExecutionPolicy Bypass -File scripts/set-domain.ps1 -Domain gis.yourdomain.com
```

- 域名必须已解析到服务器公网 IP；国内服务器需 ICP 备案，否则 80/443 被运营商拦截。
- 证书：certbot 申请（见第 3 节）。**申请前**容器用自签占位证书兜底可先跑通
  HTTPS 全链路（浏览器提示不受信属预期）；申请后无需改动自动用真实证书。

### 9.3 CityEngine / GeoScene 部署模式（二选一）

| 模式 | 是否需 GeoScene/CityEngine | 说明 |
|------|---------------------------|------|
| **A. 只做指标分析**（推荐起步） | 否 | 全部 `GEOSCENE_*` / `CITYENGINE_*` 留空；python-gis 自动回退开源 GIS 后端（容器内已装 GeoPandas/Shapely）。适合普通云服务器直接跑。 |
| **B. 含三维发布** | 是 | 需单独一台装 GeoScene Enterprise（或 Windows + CityEngine/ArcGIS Pro）的宿主，python-gis 容器经 `host.docker.internal` 访问；门户凭据用生产专用账号，`GEOSCENE_VERIFY_SSL=true`。 |

### 9.4 生产安全参数（上线前必须从宽松默认收紧）

编辑 `.env` 确认：

- `GEOSCENE_VERIFY_SSL=true`（模板已改为 true，非自建内网门户不得回退 false）；
- `UPLOAD_AV_ENABLED=true` + 部署 ClamAV 容器，并配置 `UPLOAD_AV_URL`（病毒扫描 fail-closed）；
- `UPLOAD_GLOBAL_QUOTA_BYTES` / `UPLOAD_PER_USER_QUOTA_BYTES` 从 `-1` 改为实际上限
  （如全局 `2147483648`=2GB、每用户 `209715200`=200MB），防止磁盘被写满；
- `AUTH_RATE_LIMIT_MAX` 从 300 按真实容量下调（如 120）。

### 9.5 数据备份 / 恢复 / 监控

```bash
# 备份（pg_dump + Redis RDB + 上传目录），默认 ./backups 保留 7 天
bash scripts/backup-prod.sh ./backups 7

# 恢复
bash scripts/restore-prod.sh backups/<备份时间戳目录>

# 定时备份（crontab，每天 03:00）
0 3 * * * cd /srv/gisagent && bash scripts/backup-prod.sh ./backups 14 >> logs/backup.log 2>&1
```

建议同时：
- 把 `./backups` 同步到异地（rsync / 对象存储），备份出服务器；
- 定期做一次恢复演练（新起一台容器，用 `restore-prod.sh` 验证可回滚）；
- 监控告警：`docker compose ps` 任一非 healthy 即告警（可挂 uptime-kuma / 脚本 + 钉钉/企微机器人）；
  磁盘使用率 > 80% 告警。

### 9.6 服务器规格（建议）

| 部署模式 | 内存 | 说明 |
|----------|------|------|
| A. 只指标分析 | ≥ 8GB | Java + Postgres + Redis + Nginx 已实测在 Docker Desktop（7.6GB）全栈 healthy |
| B. 含三维发布 | ≥ 16GB | 额外承担 GeoScene/CityEngine 宿主负载 |

- 系统：Linux x86_64（Ubuntu 22.04 / Debian 12 均可）；
- 安装 Docker + Compose plugin（`docker compose version`）；
- 安全组只开放 **22 / 80 / 443**，其余端口一律不放行（内部服务无端口映射，公网不可达）；
- 数据盘建议单独挂载（备份、上传目录、数据卷落盘用）。

---

## 10. 已知边界 / 尚未落地

- **上传安全**：已实现深格式校验（魔数）、可选 ClamAV 扫描、每用户/全局配额、
  TTL 过期清理（`UPLOAD_*` 环境变量）。尚未落地对象存储与图片缩略图。
- **限流**：已实现认证用户/IP 全局限流（`AUTH_RATE_LIMIT_*`）+ 登录暴力破解锁定，
  429 响应带 `Retry-After`。
- **Python 异常脱敏**：已实现 `_server_error` 统一脱敏，客户端仅收通用提示，
  完整 traceback 仅落服务器日志。
- **CityEngine / GeoScene**：宿主服务与容器之间的目录共享依赖 bind mount，
  路径需按服务器实际布局调整；
- 三个镜像（backend / nginx / python-gis）已在 Docker Desktop 本机构建并运行验证，
  编排全栈 healthy；Docker 生态其余细节（数据卷备份、证书续期、灰度更新）首次上云前
  建议在服务器完整演练一遍。

---

## 11. 验收脚本（本地联调 / 上线巡检可复用）

后端（Java:8080）与 Python GIS（:8000）启动后，在开发机或服务器运行：

```powershell
# 从 .env 读取 AUTH_ADMIN_PASSWORD，全链路验收（开发机直连 backend:8080）
powershell -ExecutionPolicy Bypass -File scripts/verify-security-hardening.ps1

# 指定密码 / 地址 / 附加限流 429 探测
.\scripts\verify-security-hardening.bat -AdminPassword xxx -TestRateLimit

# 生产：经 Nginx HTTPS 全链路验收（须可访问 127.0.0.1 上的 nginx）
.\scripts\verify-security-hardening.bat -JavaUrl "https://127.0.0.1" -AdminPassword xxx
```

脚本已处理系统代理与自签证书：用底层 `HttpWebRequest`（显式禁用代理 + 跳过证书校验），
本地 HTTPS 联调无需额外配置。

覆盖：401（无令牌）、错误密码登录、JWT 登录与 `/me`、RBAC（`/knowledge/reload`
无令牌 401 / admin 200）、上传深检（伪造 PDF → 413 `pdf_magic_mismatch`、
合法 `.md` → 200）、`/analysis/runtime` 代理（无令牌 401 / 带令牌 200）。
默认全部通过输出 `10 passed, 0 failed`。
