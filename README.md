# GISAgent · 专业 GIS 分析引擎

面向竞赛的「LLM 智能体 + GIS 空间分析」全栈系统：用自然语言驱动规划方案生成、城市指标计算、天际线/日照分析，并通过 CityEngine / GeoScene 做三维成果发布。

## 架构

三个服务协同，前端由 Spring Boot 托管（也可独立 `npm run dev`）：

| 服务 | 技术 | 端口 | 职责 |
|------|------|------|------|
| **Java 后端** | Spring Boot | `:8080` | 主网关 / 智能体编排 / 动态代码执行安全层 |
| **Python GIS 服务** | FastAPI | `:8000` | 空间分析（指标/天际线/日照/缓冲/取楼），Overpass 取楼 |
| **Vue + ArcGIS 前端** | Vue 3 + Vite + @arcgis/core | `:5173`(dev) | 交互式地图与成果展示（独立 Vite 服务） |

```
浏览器 ──▶ Vite(:5173) ──┬─▶ /api/* 代理到 Java(:8080) ──▶ Python(:8000 /analysis/*)
                        └─▶ /analysis/* 代理到 Python(:8080)
                                │
                                └─▶ CityEngine / GeoScene（三维成果发布）
```

## 快速开始

1. 复制环境变量模板并填写密钥：

   ```bash
   cp .env.example .env      # 至少填 QWEN-APIKEY
   ```

2. 一键启动全部服务（Java / Python / Vue / 可选 Redis·pgvector）：

   ```bash
   start-dev.bat
   ```

   启动后访问：
   - 前端：http://127.0.0.1:5173
   - Java 后端：http://127.0.0.1:8080
   - Python GIS API：http://127.0.0.1:8000/analysis/runtime（交互式文档 `/docs`）

3. 仅重启 Python GIS 服务（不重启 Java、前端、Portal）：双击或在终端运行：

   ```bat
   start-python-gis.bat
   ```

## 环境变量（`.env`）

| 变量 | 说明 | 默认 |
|------|------|------|
| `QWEN-APIKEY` | DashScope/Qwen 密钥（必填） | — |
| `GIS_PYTHON_SERVICE_URL` | 前端调用的 Python 服务地址 | `http://127.0.0.1:8000/analysis` |
| `POSTGRES_*` | pgvector 向量库（记忆/检索，可选） | 本地 5432 |
| `CITYENGINE_RUNTIME_ROOT` | CityEngine 运行时根目录（**必须 ASCII 路径**） | `C:/GISAgentCityEngine` |
| `CITYENGINE_BOOT_TIMEOUT_SECONDS` | CityEngine 冷启动、Python 支持文件重建预算 | 300 |
| `CITYENGINE_AUTOMATION_TIMEOUT_SECONDS` | 启动钩子安装后的自动化启动预算 | 120 |
| `CITYENGINE_JOB_TIMEOUT_SECONDS` | 单任务总超时 | 900 |
| `CITYENGINE_MAX_INPUT_BUILDINGS` | 单个 CityEngine 作业的完整建筑输入上限 | 1200 |
| `GEOSCENE_*` | GeoScene Enterprise 发布凭据 | — |
| `DYNAMIC_EXECUTION_ENABLED` | 动态代码执行总开关 | `false` |
| `DYNAMIC_EXECUTION_AUTH_MODE` | `token`（独立令牌）或 `local`（仅回环） | `local` |
| `GIS_AGENT_EXEC_TOKEN` | token 模式下的执行令牌（不复用 QWEN-APIKEY） | 空 |
| `DYNAMIC_EXECUTION_TIMEOUT_MS` | 单次执行硬超时，超时强制终止 GraalJS | `3000` |
| `SERVER_ADDRESS` | Java 实际绑定地址，local 鉴权仅允许该/回环地址 | `127.0.0.1` |
| `FRONTEND_URL` | 后端根路径重定向目标（前端 Vite 地址） | `http://127.0.0.1:5173` |

> 后端不再托管前端构建产物。访问 `http://127.0.0.1:8080/` 会自动重定向到 `FRONTEND_URL`。前端通过 Vite 代理（`/api` → :8080、`/analysis` → :8000）与后端通信。
| `DYNAMIC_EXECUTION_MAX_*` | 输入/输出字节上限、并发、线程池大小 | 64KB / 4 |

> 动态执行安全层默认**关闭**（`false`），对竞赛本地演示最稳；仅在需要时开启。

## 前端源码与构建链路（重要）

前端**唯一源码真相源**位于仓库根目录：

```
frontend/        # Vue 3 + Vite + @arcgis/core
```

构建产物输出到 `frontend/dist/`，但不再同步进 Spring Boot 静态目录——后端不再托管前端，前端始终以独立 Vite 服务运行（生产环境可将 `frontend/dist/` 部署到任意静态托管）。

一键构建并验证（仅构建，不同步）：

```bash
scripts\build-frontend.bat        # Windows
bash scripts/build-frontend.sh    # Linux / CI
```

脚本会自动执行 `npm run build` 并验证产物存在。

## 测试

| 层 | 命令 | 说明 |
|----|------|------|
| Python（行为锁定 + API 契约） | `python -m pytest` | 纯函数 + 路由级 E2E，无需 arcpy/geopandas/shapely |
| Java（安全 + 单元） | `./mvnw -q test` | 含动态执行安全用例 |
| 前端 E2E（冒烟） | `cd share/.../frontend-arcgis1 && npm i -D @playwright/test && npx playwright install && npm run test:e2e` | 校验应用挂载 + 代理连通 |

Python 测试依赖：`pip install -r requirements-dev.txt`（含 `fastapi`/`pydantic`/`pytest`/`httpx`）。

### 本地冒烟测试

在 Java、Python 和前端服务均已启动后运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
```

脚本不会提交新的 CityEngine 作业。它检查前端和 Python 服务、地点导航是否返回 `flyTo`、`analyze_area` 是否在限定时间内返回，以及最近完成作业是否同时具备 SLPK 与 SceneServer 元数据。

## CI / CD

`.github/workflows/ci.yml` 在 push/PR 时自动运行三件事：

1. **Java** — `./mvnw -q test`
2. **Python** — `pytest`
3. **前端** — `scripts/build-frontend.sh`（构建校验）

`.github/workflows/security.yml` 额外跑 `pip-audit` 依赖漏洞扫描。

## 安全

- **动态代码执行四层防护**（仅开启 `DYNAMIC_EXECUTION_ENABLED=true` 时生效）：功能开关 → 鉴权（token/local，fail-closed） → GraalVM 沙箱 → 硬超时强制终止 + 输入/输出大小限制 + 并发限流。
- **FastAPI 输入校验**：所有 `POST` 路由均有 Pydantic 模型，非法请求体返回 `422`（而非 500），并在 `/docs` 自动生成契约。
- **依赖扫描**：`pip-audit`（见 CI security 作业）。

## 目录结构（关键部分）

```
lc4j-1(1)/
├── frontend/                    # Vue 3 前端源码真相源（唯一）
│   ├── src/                      #   App.vue、组件、命令执行器
│   │   └── components/           #   MapViewer / ChatAgent / AnalysisDashboard / ...
│   └── vite.config.js            #   /api → :8080、/analysis → :8000 代理
├── gis/                          # Python 服务四层拆分
│   ├── router.py                 #   FastAPI 路由 + Pydantic 请求模型
│   ├── service.py                #   编排：指标/天际线/日照/取楼
│   ├── adapter.py                #   后端适配：CityEngine/GeoScene/arcpy/geopandas/Overpass
│   └── model.py                  #   纯几何/JSON/太阳数学（无后端依赖）
├── src/main/resources/
│   ├── demo-case/                # 离线演示案例（AOI + 建筑 + 规则）
│   └── static/                   # [已清空] 不再使用；前端由 Vite 托管
├── main.py                       # Python GIS 服务入口
│   ├── router.py                #   FastAPI 路由 + Pydantic 请求模型
│   ├── service.py               #   编排：指标/天际线/日照/取楼
│   ├── adapter.py               #   后端适配：CityEngine/GeoScene/arcpy/geopandas/Overpass
│   └── model.py                 #   纯几何/JSON/太阳数学（无后端依赖）
├── main.py                      # 兼容 shim：从 gis.* re-export（app 等）
├── cityengine_bridge.py         # CityEngine 作业桥接（标准库级）
├── geoscene_publisher.py        # GeoScene 发布桥接（标准库级）
├── share/<快照>/frontend-arcgis1/  # 前端源码真相源
├── scripts/build-frontend.*     # 前端构建→static 同步脚本
├── tests/                       # Python pytest（行为锁定 + API 契约）
└── .github/workflows/           # CI + 安全扫描
```

## 已知限制 / 后续

- **离线演示模式**：`spatial.demo.enabled=true` 时，前端自动加载内置案例（上海某城中村更新规划演示地块，6 栋建筑 + 绿地），分析链路完全不依赖 OSM/网络。后端提供 `/api/gis/offline-case` 接口下发案例数据。
- **后端不再托管前端**：前端始终以独立 Vite 服务运行（`:5173`），后端根路径重定向到前端。构建脚本仅验证构建产物，不再同步到 `static/`。
- 前端全局事件正逐步收敛到 `src/bus.js`（替代散落的 `window.dispatchEvent(new CustomEvent(...))`）。
- 天际线/日照为**城市尺度快速筛查/辅助决策**模型，非高精度物理仿真，界面与报告均标注误差边界与数据来源。
- 三维发布（CityEngine/GeoScene）依赖本地运行时与凭据，CI 中不跑。
