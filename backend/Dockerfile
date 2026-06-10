# 后端构建镜像
FROM maven:3.8-openjdk-17 AS builder

WORKDIR /app

# 复制 Maven reactor 与模块 pom 文件
COPY pom.xml ./pom.xml
COPY backend/pom.xml ./backend/pom.xml
COPY backend-agent-api/pom.xml ./backend-agent-api/pom.xml
COPY odw-auth/pom.xml ./odw-auth/pom.xml

# 下载依赖（利用 Docker 缓存）
RUN mvn -f pom.xml -pl backend -am dependency:go-offline -B

# 复制源代码
COPY backend/src ./backend/src
COPY backend-agent-api/src ./backend-agent-api/src
COPY odw-auth/src ./odw-auth/src

# 构建应用
RUN mvn -f pom.xml -pl backend -am clean package -DskipTests

# 生产运行镜像
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 运行时健康检查依赖
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 复制构建的 jar 文件
COPY --from=builder /app/backend/target/*.jar app.jar

# 创建日志目录
RUN mkdir -p /app/logs

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

EXPOSE 8080

# JVM 参数优化
ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
