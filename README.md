# GIS Agent 项目分享包

已经排除了 `.git`、`node_modules`、`target`、`dist` 和大型 ArcGIS 构建缓存。

## 目录

- `backend-lc4j/`：Spring Boot + LangChain4j 后端。
- `frontend-arcgis1/`：Vue + ArcGIS/GeoScene 前端源码。
- `gis-engine/`：FastAPI Python GIS 引擎。

## 环境

- JDK 17+
- Node.js 20.19+ 或 22.12+
- GeoScene Pro / ArcGIS Pro Python
- Redis：默认 `localhost:6379`
- PostgreSQL：默认 `localhost:5432/vectordb`

## 环境变量

```powershell
$env:QWEN_APIKEY="你的模型 API Key"
$env:GIS_PYTHON_SERVICE_URL="http://127.0.0.1:8000/analysis"
```

## 启动顺序

1. 启动 Redis / PostgreSQL。

2. 启动 Python GIS 引擎：

```powershell
& "C:\Program Files\GeoScene\Pro\bin\Python\envs\arcgispro-py3\python.exe" ".\gis-engine\main.py"
```

检查：

```text
http://127.0.0.1:8000/analysis/runtime
```

看到 `preferred_backend: geoscene_arcpy` 表示 GeoScene/ArcPy 优先路径生效。

3. 启动后端：

```powershell
cd .\backend-lc4j
.\mvnw.cmd spring-boot:run
```

4. 启动前端：

```powershell
cd .\frontend-arcgis1
npm install
npm run dev
```

前端默认地址：

```text
http://localhost:5173
```

后端默认地址：

```text
http://localhost:8080
```

## 注意

- 后端 `src/main/resources/static/assets` 已从分享包移除，想使用后端内置静态页，需要先在 `frontend-arcgis1` 执行 `npm run build`，再把 `dist` 内容复制到后端 `src/main/resources/static/`。
- GIS 引擎优先使用 GeoScene/ArcPy，开源 GIS 库只是备用。
- 如果模型服务无法访问，Agent 对话会失败，但 Python GIS 引擎本身仍可单独测试。

