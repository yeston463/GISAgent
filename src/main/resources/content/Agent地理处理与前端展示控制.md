# Agent 地理处理操作与 Cesium 前端展示控制 — RAG 知识库

> **用途**：本文档作为 RAG 知识库，供 Agent 检索学习。包含大量具体的"用户输入 → Agent 分析 → 返回格式"示例，Agent 应严格参照这些示例进行响应。
>
> **技术栈**：Cesium + Vue3 + LangChain4j + Java Web + Redis

---

## 一、Agent 返回格式规范

Agent 每次响应**必须**同时包含两部分：

### 1.1 固定返回结构

```json
{
  "commands": [...],   // Cesium 前端指令数组（必须有）
  "reply": "..."       // 自然语言回复文字（必须有）
}
```

### 1.2 前端指令（commands）类型一览

| action | 作用 | 必填参数 |
|--------|------|----------|
| `flyTo` | 飞行定位到坐标 | `longitude, latitude, height` |
| `addGeoJson` | 加载 GeoJSON 面/线数据 | `id, geojson, fillColor, strokeColor` |
| `addEntities` | 批量添加点标注 | `entities[]` |
| `removeLayer` | 移除图层或实体 | `id` |
| `showPopup` | 在地图上显示气泡信息 | `longitude, latitude, title, content` |
| `evalCode` | 执行动态生成的 Cesium JS 代码 | `code` |
| `clearAll` | 清除所有临时图层 | 无 |

---

## 二、具体示例集合（Agent 参照学习）

> 以下每个示例均包含：用户说了什么 → Agent 应该调用哪些工具 → 最终返回什么 JSON。
> **Agent 在处理类似需求时，必须参照最接近的示例格式返回。**

---

### 示例 1：飞行定位到某个城市

**用户输入：**
> 飞到北京

**Agent 工具调用：** 无需地理处理工具，直接生成指令

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "flyTo",
      "params": {
        "longitude": 116.4074,
        "latitude": 39.9042,
        "height": 50000,
        "heading": 0,
        "pitch": -45,
        "duration": 2
      }
    }
  ],
  "reply": "已飞行至北京，当前视角高度约 50 公里。"
}
```

---

### 示例 2：飞到指定地点并添加标注点

**用户输入：**
> 标注一下上海外滩的位置

**Agent 工具调用：** `geocode("上海外滩")` → 返回坐标

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "flyTo",
      "params": {
        "longitude": 121.4907,
        "latitude": 31.2336,
        "height": 2000,
        "pitch": -35,
        "duration": 2
      }
    },
    {
      "action": "addEntities",
      "params": {
        "entities": [
          {
            "id": "poi_waitan_1711900000000",
            "longitude": 121.4907,
            "latitude": 31.2336,
            "height": 0,
            "color": "#FF5722",
            "pixelSize": 14,
            "label": "上海外滩",
            "labelColor": "#FFFFFF"
          }
        ]
      }
    }
  ],
  "reply": "已定位到上海外滩（121.49°E，31.23°N），并添加了红色标注点。"
}
```

---

### 示例 3：缓冲区分析 + 结果叠加显示

**用户输入：**
> 以天安门为中心，画出 500 米的缓冲区范围

**Agent 工具调用：**
1. `bufferAnalysis(116.3974, 39.9087, 500)` → 返回缓冲区 GeoJSON

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "addGeoJson",
      "params": {
        "id": "buffer_tiananmen_1711900000000",
        "geojson": {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {
                "type": "Polygon",
                "coordinates": [[[116.3919,39.9042],[116.3921,39.9087],"..."]]
              },
              "properties": { "name": "天安门500米缓冲区" }
            }
          ]
        },
        "fillColor": "#42A5F5",
        "fillAlpha": 0.35,
        "strokeColor": "#1565C0",
        "strokeWidth": 2,
        "clampToGround": true,
        "flyTo": true
      }
    },
    {
      "action": "showPopup",
      "params": {
        "longitude": 116.3974,
        "latitude": 39.9087,
        "title": "缓冲区分析",
        "content": "以天安门为中心，半径 500 米的缓冲区已生成。"
      }
    }
  ],
  "reply": "已在地图上绘制天安门广场 500 米缓冲区（蓝色半透明区域），覆盖面积约 0.79 平方公里。"
}
```

---

### 示例 4：空间查询 + 批量点标注

**用户输入：**
> 查询天安门广场 1 公里内的所有医院，在地图上标出来

**Agent 工具调用：**
1. `bufferAnalysis(116.3974, 39.9087, 1000)` → 缓冲区 GeoJSON
2. `spatialQuery("hospitals", bufferGeoJson, "intersects")` → 返回医院列表

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "addGeoJson",
      "params": {
        "id": "buffer_query_1711900000000",
        "geojson": { "type": "FeatureCollection", "features": ["...缓冲区..."] },
        "fillColor": "#EF9A9A",
        "fillAlpha": 0.2,
        "strokeColor": "#C62828",
        "strokeWidth": 1.5,
        "clampToGround": true,
        "flyTo": false
      }
    },
    {
      "action": "addEntities",
      "params": {
        "entities": [
          {
            "id": "hospital_001_1711900000000",
            "longitude": 116.4012,
            "latitude": 39.9120,
            "color": "#F44336",
            "pixelSize": 16,
            "label": "北京协和医院",
            "labelColor": "#FFFFFF",
            "billboard": "/icons/hospital.png"
          },
          {
            "id": "hospital_002_1711900000000",
            "longitude": 116.3890,
            "latitude": 39.9005,
            "color": "#F44336",
            "pixelSize": 16,
            "label": "北京儿童医院",
            "labelColor": "#FFFFFF",
            "billboard": "/icons/hospital.png"
          }
        ]
      }
    },
    {
      "action": "flyTo",
      "params": {
        "longitude": 116.3974,
        "latitude": 39.9087,
        "height": 5000,
        "pitch": -50,
        "duration": 2
      }
    }
  ],
  "reply": "在天安门广场 1 公里范围内，共找到 **2 家医院**：\n1. 北京协和医院\n2. 北京儿童医院\n\n已在地图上用红色图标标注，红色圆圈为查询范围。"
}
```

---

### 示例 5：多步分析 — 缓冲 + 查询 + 统计

**用户输入：**
> 查询北京三环内所有学校，按区县分色显示，并告诉我各区的数量

**Agent 工具调用：**
1. `spatialQuery("schools", thirdRingRoadGeojson, "within")` → 学校列表
2. `groupByDistrict(schoolList)` → 按区县分组统计

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "addEntities",
      "params": {
        "entities": [
          {
            "id": "school_dongcheng_001",
            "longitude": 116.4200,
            "latitude": 39.9300,
            "color": "#FF9800",
            "pixelSize": 10,
            "label": "东城实验小学",
            "group": "东城区"
          },
          {
            "id": "school_xicheng_001",
            "longitude": 116.3600,
            "latitude": 39.9200,
            "color": "#4CAF50",
            "pixelSize": 10,
            "label": "西城师范附小",
            "group": "西城区"
          }
        ],
        "colorByGroup": {
          "东城区": "#FF9800",
          "西城区": "#4CAF50",
          "朝阳区": "#2196F3",
          "海淀区": "#9C27B0"
        }
      }
    },
    {
      "action": "showPopup",
      "params": {
        "longitude": 116.3974,
        "latitude": 39.9087,
        "title": "三环内学校统计",
        "content": "东城区：23所 | 西城区：31所 | 朝阳区：45所 | 海淀区：58所"
      }
    }
  ],
  "reply": "北京三环内共统计到 **157 所学校**，按区县分色展示：\n\n| 区县 | 学校数量 | 颜色 |\n|------|---------|------|\n| 海淀区 | 58 所 | 紫色 |\n| 朝阳区 | 45 所 | 蓝色 |\n| 西城区 | 31 所 | 绿色 |\n| 东城区 | 23 所 | 橙色 |"
}
```

---

### 示例 6：加载 GeoJSON 面数据（行政区划）

**用户输入：**
> 高亮显示上海市浦东新区的边界

**Agent 工具调用：**
1. `queryAdminBoundary("上海市浦东新区")` → 边界 GeoJSON

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "addGeoJson",
      "params": {
        "id": "admin_pudong_1711900000000",
        "geojson": {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": { "type": "Polygon", "coordinates": ["...浦东边界坐标..."] },
              "properties": { "name": "浦东新区", "adcode": "310115" }
            }
          ]
        },
        "fillColor": "#FFF176",
        "fillAlpha": 0.3,
        "strokeColor": "#F9A825",
        "strokeWidth": 3,
        "clampToGround": true,
        "flyTo": true
      }
    }
  ],
  "reply": "已高亮显示上海市浦东新区行政边界（黄色描边），面积约 1210 平方公里。"
}
```

---

### 示例 7：移除已有图层

**用户输入：**
> 把刚才画的缓冲区去掉

**Agent 工具调用：** 查询会话中最近添加的缓冲区图层 ID

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "removeLayer",
      "params": {
        "id": "buffer_tiananmen_1711900000000"
      }
    }
  ],
  "reply": "已移除天安门广场缓冲区图层。"
}
```

---

### 示例 8：清空地图 + 重置视角

**用户输入：**
> 清空地图，回到全球视角

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "clearAll"
    },
    {
      "action": "flyTo",
      "params": {
        "longitude": 104.0,
        "latitude": 35.0,
        "height": 8000000,
        "pitch": -90,
        "duration": 2
      }
    }
  ],
  "reply": "已清空所有标注图层，视角已重置为全国俯视图。"
}
```

---

### 示例 9：路径分析 + 线段可视化

**用户输入：**
> 规划从北京南站到故宫的驾车路线，并在地图上画出来

**Agent 工具调用：**
1. `routeAnalysis("北京南站", "故宫", "driving")` → 路径 GeoJSON LineString

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "addGeoJson",
      "params": {
        "id": "route_bjnan_gugong_1711900000000",
        "geojson": {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  [116.3783, 39.8654],
                  [116.3820, 39.8900],
                  [116.3912, 39.9060],
                  [116.3963, 39.9163]
                ]
              },
              "properties": { "distance": 9.2, "duration": 28, "mode": "driving" }
            }
          ]
        },
        "strokeColor": "#00BCD4",
        "strokeWidth": 4,
        "clampToGround": true,
        "flyTo": true
      }
    },
    {
      "action": "addEntities",
      "params": {
        "entities": [
          {
            "id": "route_start_1711900000000",
            "longitude": 116.3783,
            "latitude": 39.8654,
            "color": "#4CAF50",
            "pixelSize": 16,
            "label": "起点：北京南站"
          },
          {
            "id": "route_end_1711900000000",
            "longitude": 116.3963,
            "latitude": 39.9163,
            "color": "#F44336",
            "pixelSize": 16,
            "label": "终点：故宫"
          }
        ]
      }
    }
  ],
  "reply": "北京南站 → 故宫驾车路线已规划完成：\n- **路线距离**：约 9.2 公里\n- **预计用时**：约 28 分钟\n- 路线已用青色线段在地图上标出，绿色为起点，红色为终点。"
}
```

---

### 示例 10：动态代码生成（evalCode）— 热力图

**用户输入：**
> 把这批出租车 GPS 数据做成热力图展示在地图上

**Agent 工具调用：**
1. `processHeatmapData(gpsPoints)` → 热力图数据点集合

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "evalCode",
      "params": {
        "code": "// Agent 生成 - 出租车 GPS 热力图\nconst heatmapData = [{lng:116.38,lat:39.92,weight:0.8},{lng:116.40,lat:39.91,weight:1.0},{lng:116.41,lat:39.93,weight:0.6}];\n\nif (!viewer._heatmapLayer) {\n  // 使用 Cesium heatmap 插件或自定义实现\n  const canvas = document.createElement('canvas');\n  // ... 绘制热力图逻辑 ...\n  viewer.imageryLayers.addImageryProvider(\n    new Cesium.SingleTileImageryProvider({\n      url: canvas.toDataURL(),\n      rectangle: Cesium.Rectangle.fromDegrees(116.0, 39.5, 116.8, 40.2)\n    })\n  );\n  viewer._heatmapLayer = true;\n}\nviewer.camera.flyTo({\n  destination: Cesium.Cartesian3.fromDegrees(116.40, 39.92, 30000),\n  duration: 2\n});"
      }
    }
  ],
  "reply": "已根据 3,241 个 GPS 点生成出租车轨迹热力图，颜色越深表示车辆密度越高，主要聚集区域在东城区、西城区交界附近。"
}
```

---

### 示例 11：叠加分析（求交集）

**用户输入：**
> 找出既在地铁 10 号线 500 米范围内，又在学区房范围内的区域

**Agent 工具调用：**
1. `bufferAnalysis("metro_line_10_geojson", 500)` → 地铁缓冲区
2. `queryLayer("school_districts")` → 学区面数据
3. `intersectGeometry(metroBuffer, schoolDistricts)` → 交集区域

**Agent 返回：**

```json
{
  "commands": [
    {
      "action": "addGeoJson",
      "params": {
        "id": "metro10_buffer_1711900000000",
        "geojson": { "type": "FeatureCollection", "features": ["...地铁缓冲区..."] },
        "fillColor": "#B39DDB",
        "fillAlpha": 0.2,
        "strokeColor": "#7E57C2",
        "strokeWidth": 1,
        "clampToGround": true,
        "flyTo": false
      }
    },
    {
      "action": "addGeoJson",
      "params": {
        "id": "school_district_1711900000000",
        "geojson": { "type": "FeatureCollection", "features": ["...学区范围..."] },
        "fillColor": "#A5D6A7",
        "fillAlpha": 0.2,
        "strokeColor": "#388E3C",
        "strokeWidth": 1,
        "clampToGround": true,
        "flyTo": false
      }
    },
    {
      "action": "addGeoJson",
      "params": {
        "id": "intersection_result_1711900000000",
        "geojson": { "type": "FeatureCollection", "features": ["...交集区域..."] },
        "fillColor": "#FFD54F",
        "fillAlpha": 0.6,
        "strokeColor": "#FF8F00",
        "strokeWidth": 2.5,
        "clampToGround": true,
        "flyTo": true
      }
    },
    {
      "action": "showPopup",
      "params": {
        "longitude": 116.4200,
        "latitude": 39.9300,
        "title": "叠加分析结果",
        "content": "符合条件区域：7块，总面积约 12.4 平方公里"
      }
    }
  ],
  "reply": "叠加分析完成！**同时满足**地铁10号线500米范围内且在学区房范围内的区域共 **7 块**，总面积约 **12.4 平方公里**，已用橙黄色高亮显示。\n\n- 紫色：地铁10号线缓冲区\n- 绿色：学区范围\n- 橙黄色：两者交集（推荐区域）"
}
```

---

## 三、reply 字段写作规范

Agent 在编写 `reply` 字段时必须遵守以下规则：

### 3.1 必须包含的信息

| 场景 | reply 必须包含 |
|------|----------------|
| 空间查询 | 查询到的数量、主要结果名称 |
| 缓冲区分析 | 缓冲半径、覆盖面积（估算） |
| 路径分析 | 距离、时间、路线说明 |
| 叠加分析 | 各图层含义、结果说明 |
| 定位/飞行 | 目标地点名称、经纬度 |

### 3.2 格式要求

- 数字加粗：`**23 所**`、`**9.2 公里**`
- 多项结果用 Markdown 表格或列表
- 图例说明（颜色含义）必须在末尾注明
- 禁止出现 "我已经调用了工具" 等技术性表述
- 禁止暴露工具名称（如 `bufferAnalysis`、`spatialQuery`）

### 3.3 reply 示例对比

❌ **错误写法（禁止）：**
```
我调用了 bufferAnalysis 工具，输入经度116.39、纬度39.90、半径500，
返回了一个GeoJSON Polygon，然后我调用了spatialQuery查询hospitals图层...
```

✅ **正确写法：**
```
已在天安门广场周边生成 **500 米缓冲区**，范围内共找到 **3 家医院**：
1. 北京协和医院
2. 北京儿童医院
3. 北京同仁医院

红色区域为查询范围，红色图标为医院位置。
```

---

## 四、commands 字段生成规范

### 4.1 ID 命名规则（必须遵守）

```
格式：{类型}_{简短描述}_{时间戳毫秒}

示例：
- buffer_tiananmen_1711900000000
- hospital_search_result_1711900000000
- route_start_1711900000000
- admin_boundary_pudong_1711900000000
```

**目的**：保证唯一性，便于后续 `removeLayer` 精确操作。

### 4.2 颜色规范

| 图层用途 | 推荐填充色 | 推荐描边色 |
|---------|-----------|-----------|
| 缓冲区 | `#42A5F5`（蓝）| `#1565C0` |
| 查询结果（面） | `#EF9A9A`（红）| `#C62828` |
| 路径线 | `#00BCD4`（青）| `#00ACC1` |
| 行政边界 | `#FFF176`（黄）| `#F9A825` |
| 分析结果（高亮） | `#FFD54F`（橙黄）| `#FF8F00` |
| 叠加交集 | `#FFD54F`（橙黄）| `#FF8F00` |

### 4.3 flyTo 时机规范

- 空间查询结果 < 5 个：`flyTo` 设为 `true`，飞到结果范围
- 空间查询结果 >= 5 个：单独追加 `flyTo` 指令，飞到数据中心点
- 缓冲区分析：在 `addGeoJson` 中设 `"flyTo": true`
- 移除图层：不需要 `flyTo`
- 清空地图：单独发 `flyTo` 到全国视角

---

## 五、错误处理示例

### 5.1 找不到数据时

**用户输入：**
> 查询南极洲的超市

**Agent 返回：**

```json
{
  "commands": [],
  "reply": "抱歉，未在数据库中找到南极洲范围内的超市数据。目前数据覆盖范围为中国大陆地区，您可以尝试查询国内城市的相关设施。"
}
```

### 5.2 需要更多信息时

**用户输入：**
> 帮我查一下附近的学校

**Agent 返回：**

```json
{
  "commands": [],
  "reply": "请问您想查询哪个地点附近的学校？可以告诉我：\n1. 具体地址或地名（如"北京市朝阳区望京"）\n2. 查询半径（如500米、1公里）"
}
```

---

## 六、完整工作流示意

```
用户："查找上海陆家嘴2公里内的银行并标注"
         ↓
Agent 思考：
  1. 需要对陆家嘴坐标做2000米缓冲
  2. 查询银行图层
  3. 批量添加点标注
  4. 飞到结果视角
         ↓
工具调用顺序：
  bufferAnalysis(121.4970, 31.2397, 2000)
  → spatialQuery("banks", buffer, "intersects")
  → 返回 18 家银行
         ↓
生成 commands：
  [addGeoJson(缓冲区), addEntities(18个银行点), flyTo(陆家嘴)]
         ↓
生成 reply：
  "陆家嘴 2 公里范围内共有 **18 家银行**，已用蓝色图标标注..."
         ↓
返回完整 JSON 给后端 → SSE 推送给前端 → Cesium 执行渲染
```

---
备注：1.“除了获取你不知道的精确地理坐标外，所有的地图操作请通过返回 commands JSON 指令来实现，不要调用本地工具函数。”
2.需要使用地理处理的全部采用@Tool注解的工具函数




*本文档为 RAG 知识库文件，最后更新：2026-04-01*
*技术栈：Cesium + Vue3 + LangChain4j + Java Web + Redis*
