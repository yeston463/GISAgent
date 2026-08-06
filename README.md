# GISAgent · 一句话完成地块分析

## 解决什么问题

规划师分析一个地块的容积率、建筑密度、日照和天际线，通常要打开 **CAD 算指标 → GIS 取建筑 → SketchUp 看三维 → PPT 写结论**——切 5 个软件，耗时 30 分钟以上，数据在工具间对不齐，结论容易遗漏或编造。

## 我们的方案

**说一句话，45 秒完成全流程。** 用户输入"分析上海这块地的容积率和天际线"，系统自动定位地块、获取建筑、计算指标、生成三维视图、输出带溯源证据的分析报告。

**核心不是 LLM 调工具，而是受约束、可追溯的空间分析工作流：**

| 环节 | 机制 | 保障 |
|------|------|------|
| 意图理解 | LLM 强制工具调用，路由到能力目录白名单 | 模型不能随意编造分析类型 |
| 数据获取 | 优先 OSM/服务端取数，失败降级到离线案例 | 无网络时仍可演示演示 |
| 指标计算 | GIS 引擎（Python）计算，LLM 不碰数字 | 杜绝模型幻觉污染专业结论 |
| 结果校验 | 约束校验层检查容积率、密度、高度是否越界 | 异常值自动标记 |
| 证据溯源 | 每条记录带 memoryId、时间戳、数据来源、会话隔离 | A 用户看不到 B 用户的分析 |

## 三类用户

- **规划师/设计师**：快速筛查方案指标，多方案比选
- **教师/学生**：规划课程教学演示，理解指标计算过程
- **评审方**：验证分析结论的可复现性与数据来源

## 关键差异（有实测对比）

| 维度 | 传统流程 | GISAgent |
|------|----------|----------|
| 操作步骤 | 5+ 软件切换 | 一个对话窗口 |
| 耗时 | ~30 分钟 | ~45 秒 |
| 数据一致性 | 手工传递，易出错 | 全链路统一上下文 |
| 结果可复现 | 依赖个人操作 | 固定输入 → 固定输出，自动测试锁定 |
| 离线能力 | 依赖专业软件许可 | 内置 6 栋建筑案例，无网络可跑 |

**离线验证数据**（内置案例，纯标准库计算，零外部依赖）：

```
用地面积：126,264 m²
总建筑面积：479,807 m²
容积率 FAR：3.8
建筑密度：24.0%
建筑数量：6
数据确定性：SHA-256 锁定，可复现
```

## 30 秒体验

```bash
# 1. 配置密钥
cp .env.example .env          # 填 QWEN-APIKEY

# 2. 一键启动
start-dev.bat                 # Java :8080 + Python :8000 + Vue :5173

# 3. 浏览器打开
# http://127.0.0.1:5173
# 输入："分析上海这块地的容积率和天际线"
```

**无网络/无密钥？** 系统自动加载内置离线案例，前端 demo 模式一键验证完整分析链路。

## 一键验收（离线模式）

```powershell
scripts\acceptance-offline.ps1
```

自动完成：启动 Python GIS → 启动 Java 后端 → 加载离线案例 → 计算指标 → 比对基准值。全程不依赖 OSM/LLM。

## 技术架构

三个服务协同，前端独立运行：

```
浏览器 ──▶ Vite(:5173) ──┬─▶ /api/* 代理到 Java(:8080) ──▶ Python(:8000 /analysis/*)
                        └─▶ /analysis/* 代理到 Python(:8000)
                                │
                                └─▶ CityEngine / GeoScene（三维成果发布）
```

| 服务 | 技术 | 端口 | 职责 |
|------|------|------|------|
| **Vue 前端** | Vue 3 + Vite + @arcgis/core | `:5173` | 交互式地图 + 对话分析（独立 Vite 服务） |
| **Java 后端** | Spring Boot 3.3 + LangChain4j | `:8080` | Agent 编排 / 工具注册 / 安全校验 / 溯源记录 |
| **Python GIS** | FastAPI + Pydantic | `:8000` | 指标计算 / 天际线 / 日照 / 取楼 |

## 能力目录

- `urban_metrics` — 容积率、建筑密度、建筑覆盖率、高度统计、层数分布
- `skyline_analysis` — 天际线轮廓筛查（城市尺度近似）
- `sunlight_analysis` — 日照阴影筛查（城市尺度近似）
- `flood_analysis` — 内涝积水筛查（DEM + 降雨情景）
- `site_selection` — 多准则选址适宜性评估

## 目录结构

```
├── frontend/                    # Vue 3 前端源码（唯一真相源）
│   ├── src/components/          # MapViewer / ChatAgent / AnalysisDashboard
│   └── vite.config.js           # /api → :8080, /analysis → :8000 代理
├── gis/                         # Python GIS 服务四层拆分
│   ├── router.py                # FastAPI 路由 + Pydantic 请求模型
│   ├── service.py               # 编排：指标/天际线/日照/取楼
│   ├── adapter.py               # 后端适配：Overpass/GeoPandas/ArcPy/GeoScene
│   └── model.py                 # 纯几何/太阳数学（零后端依赖）
├── src/main/resources/
│   ├── demo-case/               # 离线演示案例（6 栋建筑 + 规则）
│   │   ├── case.json            # 含 dataIdentity（synthetic + SHA-256）
│   │   └── rules.json           # R2 用地控制指标（effective=false）
│   └── static/                  # [已清空] 不再使用
├── main.py                      # Python 入口（gis.* re-export）
├── cityengine_bridge.py         # CityEngine 三维成果桥接
├── geoscene_publisher.py        # GeoScene Enterprise 发布
├── scripts/
│   ├── build-frontend.*         # 前端构建验证（不同步 static）
│   ├── acceptance-offline.ps1   # 离线一键验收
│   └── smoke-test.ps1           # 本地冒烟测试
├── tests/                       # Python pytest（确定性 + API 契约）
├── src/test/java/               # Java 单元测试（安全 + 合同 + 溯源）
└── .github/workflows/           # CI + 安全扫描
```

## 测试覆盖

**系统能做什么 → 怎么证明**（评委视角，非按测试文件分类）：

| 能力 | 验证方式 |
|------|----------|
| 一句话出容积率/密度/高度/日照/天际线 | 离线指标测试 + Java 空间规划合同 |
| 结果确定性、无网络也可复现 | SHA-256 锁定案例 + 离线验收脚本 |
| 方案比选可解释（含评分权重溯源） | ScenarioEvaluator 暴露 scoreBreakdown |
| 多用户并发 + 编辑锁 + 版本冲突防护 | Java 并发/合同测试 |
| 三维发布到 GeoScene（Feature/WebMap/WebScene） | mock Portal 端到端（网络边界替换为桩） |
| 增量分析只算变化建筑 | 增量测试断言不规则即复用 |
| 动态代码执行安全边界 | Java 动态执行防护测试（默认关闭） |

按执行层（命令）看：

| 层 | 命令 | 说明 |
|----|------|------|
| Python（确定性 + 契约） | `python -m pytest` | **120 项**：离线指标 / API 契约 / 主流程锁定 / CityEngine 几何 / GeoScene 发布 / 增量 / 发布目标幂等（8 项发布目标以 mock Portal 端到端跑通） |
| Java（安全 + 合同） | `./mvnw -q test` | 动态执行防护 + 空间规划合同 + 溯源隔离 |
| 前端 E2E | `cd frontend && npm run test:e2e` | 应用挂载 + 代理连通 |
| 离线验收 | `scripts\acceptance-offline.ps1` | 启动 → 加载离线案例 → 指标比对 |

CI（`.github/workflows/ci.yml`）：push/PR 自动跑 Java 测试 + Python 测试 + 前端构建校验。

## 安全设计

- **动态代码执行四层防护**（默认关闭）：开关 → 鉴权（token/local，fail-closed）→ GraalVM 沙箱 → 硬超时终止 + 输入/输出限制 + 并发限流
- **FastAPI 全路由 Pydantic 校验**：非法请求体返回 `422`，不暴露内部错误
- **会话隔离**：分析记录带 memoryId，跨会话不可见
- **依赖扫描**：`pip-audit`（CI security 作业）

## 已知限制

- 天际线/日照为**城市尺度快速筛查/辅助决策**模型，非高精度物理仿真
- 三维发布（CityEngine/GeoScene）依赖本地运行时与凭据，CI 中不跑
- 动态代码执行默认关闭，竞赛演示不建议开启
