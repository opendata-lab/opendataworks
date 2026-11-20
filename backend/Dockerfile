# 后端构建镜像
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Optional Maven Central mirror for CI (falls back to Central; unset for local builds).
ARG MAVEN_CENTRAL_MIRROR=""
RUN if [ -n "$MAVEN_CENTRAL_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '%s\n' \
            '<settings>' \
            '  <profiles>' \
            '    <profile>' \
            '      <id>central-mirror</id>' \
            '      <repositories>' \
            '        <repository>' \
            '          <id>central-mirror</id>' \
            '          <url>'"$MAVEN_CENTRAL_MIRROR"'</url>' \
            '          <releases><enabled>true</enabled></releases>' \
            '          <snapshots><enabled>false</enabled></snapshots>' \
            '        </repository>' \
            '      </repositories>' \
            '      <pluginRepositories>' \
            '        <pluginRepository>' \
            '          <id>central-mirror</id>' \
            '          <url>'"$MAVEN_CENTRAL_MIRROR"'</url>' \
            '          <releases><enabled>true</enabled></releases>' \
            '          <snapshots><enabled>false</enabled></snapshots>' \
            '        </pluginRepository>' \
            '      </pluginRepositories>' \
            '    </profile>' \
            '  </profiles>' \
            '  <activeProfiles>' \
            '    <activeProfile>central-mirror</activeProfile>' \
            '  </activeProfiles>' \
            '</settings>' > /root/.m2/settings.xml; \
    fi

# Cache dependencies and build plugins in a BuildKit layer (cache mounts are not exported to GHA cache).
COPY backend/pom.xml ./backend/pom.xml
COPY backend/core/pom.xml ./backend/core/pom.xml
COPY backend/api/pom.xml ./backend/api/pom.xml
COPY backend/mcp-server/pom.xml ./backend/mcp-server/pom.xml
COPY backend/app/pom.xml ./backend/app/pom.xml
RUN mvn -B -ntp -f backend/pom.xml package -Dmaven.test.skip=true -Dspring-boot.repackage.skip=true \
    && rm -rf backend/*/target

COPY backend ./backend
RUN mvn -B -ntp -f backend/pom.xml package -Dmaven.test.skip=true

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
