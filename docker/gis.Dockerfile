# Python GIS / 分析服务镜像
# 说明：容器内运行 FastAPI。CityEngine / GeoScene 在宿主机原生运行，
#       本容器通过 host.docker.internal 反向访问（见 compose-prod.yaml）。
FROM python:3.11-slim

WORKDIR /workspace

RUN groupadd -r gis && useradd -r -g gis -d /workspace gis \
    && mkdir -p /workspace/cityengine-workspace /workspace/geoscene-upload-temp \
    && chown -R gis:gis /workspace

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
# 开源 GIS 几何引擎（容器内无法用 ArcPy，OpenGeo 兜底需要这些库）
COPY requirements-gis.txt ./
RUN pip install --no-cache-dir -r requirements-gis.txt

COPY --chown=gis:gis main.py cityengine_bridge.py geoscene_publisher.py ./
COPY --chown=gis:gis gis ./gis

EXPOSE 8000
USER gis
# 监听内网，生产不对外暴露（由 Nginx -> backend -> python-gis 链路访问）
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
