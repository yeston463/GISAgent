# 后端镜像：Maven 构建 -> 轻量 JRE 运行时
# 说明：运行时需写入 cityengine-workspace（GIS 上传文件），生产建议挂载数据卷。
# 国内网络拉 Docker Hub 不通时，可用 docker.m.daocloud.io 等加速器前缀：
#   docker compose -f compose-prod.yaml build --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre
ARG MAVEN_MIRROR_URL=
FROM ${MAVEN_IMAGE} AS build
ARG MAVEN_MIRROR_URL
WORKDIR /build
COPY pom.xml .
COPY src ./src
COPY .mvn ./.mvn
COPY mvnw mvnw.cmd ./
# 跳过测试与测试编译，加快构建（测试已在 CI/本地单独执行）
RUN --mount=type=cache,target=/root/.m2 \
    set -eux; \
    if [ -n "${MAVEN_MIRROR_URL}" ]; then \
      printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">' \
        '  <mirrors><mirror><id>configured-mirror</id><mirrorOf>*</mirrorOf><url>'"${MAVEN_MIRROR_URL}"'</url></mirror></mirrors>' \
        '</settings>' > /tmp/maven-settings.xml; \
      mvn -B -s /tmp/maven-settings.xml -Dmaven.test.skip=true package -DskipTests; \
    else \
      mvn -B -Dmaven.test.skip=true package -DskipTests; \
    fi

FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=build /build/target/lc4j-1-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
# 安装健康检查探测工具（基础镜像默认不带 curl/wget）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app -d /app app \
    && mkdir -p /app/uploads && chown -R app:app /app
USER app
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=12 \
  CMD ["curl","-fsS","http://127.0.0.1:8080/actuator/health"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
