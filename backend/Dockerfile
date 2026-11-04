# 后端构建镜像
FROM --platform=$BUILDPLATFORM maven:3.8-openjdk-17 AS builder

WORKDIR /app

# Build the reactor once; retain downloaded dependencies across local/CI builds.
COPY backend ./backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml package -Dmaven.test.skip=true

# 生产运行镜像
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 运行时健康检查依赖
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 复制构建的 jar 文件
COPY --from=builder /app/backend/app/target/*.jar app.jar

# 创建日志目录
RUN mkdir -p /app/logs

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

EXPOSE 8080

# JVM 参数优化
ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
