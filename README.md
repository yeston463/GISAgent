# GISAgent

GISAgent 是一个面向城市规划场景的 AI 辅助 GIS 分析系统。用户可在地图中绘制或选择区域，通过自然语言完成建筑指标、天际线、日照、内涝风险、选址和 CityEngine 三维成果分析。

## 主要能力

- Vue 3 + ArcGIS Maps SDK 前端地图与对话工作台
- Spring Boot Agent：受能力目录约束的意图决策、工具编排、会话与权限控制
- FastAPI GIS 引擎：AOI、建筑、容积率、密度、天际线、日照和 DEM 内涝分析
- CityEngine / GeoScene 三维成果生成与发布链路
- Redis 与 pgvector：任务状态、缓存和长期记忆
- 管理员知识图谱工作台：手工填写、模板填写、AI 生成候选、校验、发布、回滚与远程图谱刷新

## 本地启动

### 前置条件

- Windows 10/11
- Docker Desktop（Redis 和 pgvector）
- JDK 17、Node.js 20+、Python 3.11+ 或项目 `.venv`
- 可选：GeoScene Pro / CityEngine，用于 ArcPy 优先计算和三维成果

1. 复制环境变量模板：

   ```powershell
   Copy-Item .env.example .env
   ```

2. 在 `.env` 至少设置：

   ```ini
   AUTH_JWT_SECRET=长度不少于32字节的随机字符串
   AUTH_ADMIN_PASSWORD=管理员密码
   POSTGRES_PASSWORD=数据库密码
   REDIS_PASSWORD=Redis密码
   QWEN-APIKEY=你的模型密钥
   ```

3. 双击 `start-dev.bat`，它会依次启动 Docker、Redis、pgvector、Python GIS、Java 后端和前端。

4. 浏览器访问 <http://127.0.0.1:5173>。

若只需启动基础容器，双击 `start-docker.bat`。容器端口仅绑定本机：Redis `6379`，pgvector `5432`。

## 知识图谱配置

管理员登录后，在浏览器的“知识图谱工作台”中可填写能力 ID、别名、用途、验收语句与 JSON，或让 AI 先生成候选草稿；候选必须通过校验后才会发布。

新增能力只能复用已有、已审核的执行契约，不能由图谱注入任意工具或操作。远程图谱配置方式：

```ini
SPATIAL_KNOWLEDGE_GRAPH_URL=https://example.com/spatial-capabilities.json
SPATIAL_KNOWLEDGE_GRAPH_REFRESH_TTL_SECONDS=300
SPATIAL_KNOWLEDGE_GRAPH_ADMIN_MODE=token
SPATIAL_KNOWLEDGE_GRAPH_ADMIN_TOKEN=独立随机令牌
```

可用模板：`src/main/resources/spatial-capabilities.remote-template.json`。未设置远程地址时，系统继续使用内置且经过审核的图谱。

## 测试与构建

```powershell
# Python
.\.venv\Scripts\python.exe -m pytest -q

# Java
.\mvnw.cmd -q test

# 前端生产构建
Set-Location frontend
npm.cmd run build

# 已启动服务的全量冒烟检查
Set-Location ..
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
```

## 生产部署

使用 `.env.production.example` 创建生产 `.env`，配置强密码、TLS 域名和所有外部凭据后执行：

```powershell
docker compose -f compose-prod.yaml build
docker compose -f compose-prod.yaml up -d
```

生产环境仅由 Nginx 对外提供 `80/443`；Java、Python GIS、Redis 与 PostgreSQL 在 Docker 内网通信。请勿提交 `.env`、日志、构建产物、运行时工作区或 CityEngine/GeoScene 凭据。
