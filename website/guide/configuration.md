# 配置说明

## 后端配置

后端配置文件位于 `backend/src/main/resources/application.yml`。

### 数据库配置

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/opendataworks?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: opendataworks
    password: opendataworks123
```

### MyBatis Plus

```yaml
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: com.onedata.portal.entity
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

### DolphinScheduler

DolphinScheduler 配置由数据库 `dolphin_config` 表管理，推荐通过前端「系统管理 → Dolphin 配置」维护。

### 日志

```yaml
logging:
  level:
    com.onedata.portal: debug
    org.springframework.web: info
```

## 前端配置

前端配置文件位于 `frontend/vite.config.js`。

### 代理配置

```javascript
export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api/v1/dataagent': {
        target: 'http://localhost:8900',
        changeOrigin: true
      },
      '/api/v1/nl2sql': {
        target: 'http://localhost:8900',
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

## 环境变量

Docker 部署时通过 `deploy/.env` 配置环境变量：

```bash
# 数据库
MYSQL_ROOT_PASSWORD=root123
MYSQL_DATABASE=opendataworks
MYSQL_USER=opendataworks
MYSQL_PASSWORD=opendataworks123

# 服务端口
BACKEND_PORT=8080
FRONTEND_PORT=8081
DATAAGENT_PORT=8900
```

::: tip
完整的环境变量列表参见 `deploy/.env.example`。
:::
