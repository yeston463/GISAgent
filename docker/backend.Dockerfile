# 后端镜像：Maven 构建 -> 轻量 JRE 运行时
# 说明：运行时需写入 cityengine-workspace（GIS 上传文件），生产建议挂载数据卷。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
COPY .mvn ./.mvn
COPY mvnw mvnw.cmd ./
# 跳过测试与测试编译，加快构建（测试已在 CI/本地单独执行）
RUN mvn -B -Dmaven.test.skip=true package -DskipTests

FROM eclipse-temurin:17-jre
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
