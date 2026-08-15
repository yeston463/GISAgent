# GISAgent

GISAgent 是面向城市规划与城市更新的企业级 AI 辅助 GIS 分析平台。系统采用可私有化部署架构，用户可在地图中绘制或选择区域，通过自然语言完成建筑指标、天际线、日照、内涝风险、选址和 CityEngine 三维成果分析；业务数据、会话上下文和分析过程可保留在本地受控环境中。

## 主要能力

- Vue 3 + GeoScene Maps SDK for JavaScript（@geoscene/core）前端地图与对话工作台
- Spring Boot Agent：受能力目录约束的意图决策、工具编排、会话与权限控制
- 企业级治理：已审核能力图谱、受控工具调用、运行溯源与可复核分析结果
- FastAPI GIS 引擎：AOI、建筑、容积率、密度、天际线、日照和 DEM 内涝分析
- CityEngine / GeoScene 三维成果生成与发布链路
- Redis 与 pgvector：任务状态、缓存和长期记忆
- 管理员知识图谱工作台：手工填写、模板填写、AI 生成候选、校验、发布、回滚与远程图谱刷新

## 本地启动

### 前置条件

- Windows 10/11
- Docker Desktop（Redis 和 pgvector）
- JDK 17、Node.js 20+、Python 3.11+ 或项目 `.venv`
- 推荐：GeoScene Pro（ArcPy 计算后端优先）、CityEngine 2025.1（三维成果生成）
- 三维发布链路需要：GeoScene Enterprise（Portal + Server + Data Store，含对象存储）

1. 复制环境变量模板：

   ```powershell
   Copy-Item .env.example .env
   ```

2. 在 `.env` 至少设置（完整说明见 `.env.example`）：

   ```ini
   POSTGRES_PASSWORD=数据库密码
   REDIS_PASSWORD=Redis密码
   QWEN-APIKEY=你的DashScope密钥      # embedding 与兜底模型，必填
   DEEPSEEK_API_KEY=你的DeepSeek密钥  # 主对话/路由模型（优先使用）
   SPATIAL_DEMO_ENABLED=true          # 离线演示数据开关，验收脚本依赖，务必开启
   ```

3. 配置本机域名解析（hosts 文件，`C:\Windows\System32\drivers\etc\hosts`，需管理员）：
   本机企业组件统一使用完全限定域名 `product.geosceneenterprise.cn`，请将本机 IP 与域名写入 hosts：

   ```
   127.0.0.1  product.geosceneenterprise.cn
   127.0.0.1  PRODUCT.GEOSCENEENTERPRISE.CN
   ```

   使用 `127.0.0.1` 可避免 DHCP 换 IP 导致解析失效；若需从其他电脑访问，改为本机固定 IP。

4. （可选）双击 `start-docker.bat` 启动 Redis 和 pgvector；再双击 `start-dev.bat` 启动 Python GIS、Java 后端和前端。`start-dev.bat` 默认不启动 Docker。

5. 浏览器访问 <http://127.0.0.1:5173>。

若只需启动基础容器，双击 `start-docker.bat`。容器端口仅绑定本机：Redis `6379`，pgvector `5432`。

## 本地服务

`start-dev.bat` 不会构建完整应用容器。它直接在 Windows 主机启动以下服务：

| 服务 | 地址 | 启动方式 |
| --- | --- | --- |
| Vue 前端 | <http://127.0.0.1:5173> | Vite |
| Java Agent 后端 | <http://127.0.0.1:8080> | Spring Boot |
| Python GIS API | <http://127.0.0.1:8000/analysis/runtime> | FastAPI |
| Redis | `127.0.0.1:6379` | Docker Compose |
| pgvector | `127.0.0.1:5432` | Docker Compose |

前端通过 Vite 代理访问 Java 与 Python 服务。关闭各自打开的终端窗口即可停止前端、Java 或 GIS 服务；基础容器可使用 `docker compose -p lc4j -f compose.yaml down` 停止。

## 知识图谱配置

本地免登录模式下，打开“知识图谱工作台”即可可填写能力 ID、别名、用途、验收语句与 JSON，或让 AI 先生成候选草稿；候选必须通过校验后才会发布。

新增能力只能复用已有、已审核的执行契约，不能由图谱注入任意工具或操作。远程图谱配置方式：

```ini
SPATIAL_KNOWLEDGE_GRAPH_URL=https://example.com/spatial-capabilities.json
SPATIAL_KNOWLEDGE_GRAPH_REFRESH_TTL_SECONDS=300
SPATIAL_KNOWLEDGE_GRAPH_ADMIN_MODE=token
SPATIAL_KNOWLEDGE_GRAPH_ADMIN_TOKEN=独立随机令牌
```

可用模板：`src/main/resources/spatial-capabilities.remote-template.json`。未设置远程地址时，系统继续使用内置且经过审核的图谱。

## 三维成果发布（CityEngine → GeoScene Enterprise）

对话要求"生成/发布三维成果"即可完成 CityEngine 建模、SLPK 导出与 Enterprise 场景服务发布。除前置条件中的 Enterprise 组件外，`.env` 还需（详见 `.env.example`）：

```ini
GIS_PYTHON_EXE=C:\Program Files\GeoScene\Pro\bin\Python\envs\arcgispro-py3\python.exe
CITYENGINE_RUNTIME_ROOT=C:/GISAgentCityEngine
GEOSCENE_PORTAL_URL=https://localhost:7443/geoscene
GEOSCENE_PORTAL_USERNAME=门户管理员
GEOSCENE_PORTAL_PASSWORD=门户密码
GEOSCENE_SERVER_ADMIN_URL=https://localhost:6443/geoscene/admin
GEOSCENE_OBJECT_STORE_ID=对象存储ID（describedatastore 输出中的 Object Store 名称）
GEOSCENE_OBJECT_STORE_MACHINE=数据存储机器名
```

注意：若 GeoScene Data Store 的 Ozone 对象存储因发行包缺失 `libozone_rocksdb_tools.dll` 而无法启动，在 `C:\geoscenedatastore\ozonedata\etc\hadoop\ozone-site.xml` 添加以下配置即可绕开（详见 `docs/部署与运维说明.md`）：

```xml
<property><name>ozone.om.snapshot.load.native.lib</name><value>false</value></property>
<property><name>ozone.om.snapshot.diff.disable.native.libs</name><value>true</value></property>
```

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

# 离线验收（需 SPATIAL_DEMO_ENABLED=true；自动启停 8000/8080）
powershell -ExecutionPolicy Bypass -File scripts\acceptance-offline.ps1
```

完整的新机器部署步骤、排障与运维说明见 [`docs/部署与运维说明.md`](docs/部署与运维说明.md)。

请勿提交 `.env`、日志、构建产物、运行时工作区或 CityEngine/GeoScene 凭据。

