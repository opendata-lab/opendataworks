# MySQL 初始化脚本目录

此目录用于存放 MySQL 容器首次启动时自动执行的初始化 SQL 脚本。

## 工作原理

Docker Compose 会将此目录挂载到 MySQL 容器的 `/docker-entrypoint-initdb.d/` 目录。MySQL 官方镜像会在容器首次启动时（数据库为空时）自动执行该目录下的所有 `.sql`、`.sh`、`.sql.gz` 文件。

## 文件说明

- `01-init-database.sql`: 数据库和用户的初始化脚本
  - 确保数据库使用正确的字符集（utf8mb4）
  - 创建 `opendataworks` 应用用户，并设置默认权限

## 注意事项

1. **仅首次启动执行**: 这些脚本只在数据库为空时执行一次。如果数据库已存在数据（数据保存在 `mysql-data` volume 中），脚本不会再次执行。

2. **重启 Docker 无影响**: 
   - ✅ **正常重启**（`docker compose restart` 或 `docker compose down/up`）：数据保存在 volume 中，脚本**不会**再次执行，**不会有任何影响**
   - ✅ **停止后启动**：如果 volume 存在，脚本**不会**执行，数据保持不变
   - ⚠️ **完全重新初始化**：如果需要重新执行脚本，需要先删除 volume：
     ```bash
     docker compose down -v  # 删除所有 volume（包括数据）
     docker compose up -d    # 重新启动，脚本会再次执行
     ```

3. **数据库初始化与迁移分工**:
   - 本目录只负责创建 `opendataworks` 数据库、用户和授权
   - 表结构由 backend 的 Flyway 迁移管理
   - DataAgent 的数据库随 opendataagent 仓库单独部署，不在此初始化

4. **文件命名**: 建议使用数字前缀（如 `01-`, `02-`）来确保执行顺序。

5. **字符集**: 所有脚本应使用 `utf8mb4` 字符集。

6. **升级已有 volume**:
   - 如果 `mysql-data` 已存在，初始化脚本不会再次执行。
   - 旧版本留下的 `dataagent` 库和用户不再被本仓库使用；迁移到 opendataagent 部署后可自行备份并删除。

## 与手册的关系

此目录中的脚本对应快速开始指南中需要用户手动执行的数据库创建步骤：

```sql
CREATE DATABASE opendataworks DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';
FLUSH PRIVILEGES;
```

使用 Docker Compose 部署时，这些步骤会自动执行，无需手动操作。
