# RBAC权限管理系统 - 快速开始

## 🚀 5分钟快速上手

### 步骤1: 运行数据库迁移

```bash
cd backend
mvn flyway:migrate
```

这将创建以下表：
- `platform_users` - 平台用户
- `doris_database_users` - Doris数据库用户配置
- `user_database_permissions` - 用户权限映射

### 步骤2: 在Doris中创建数据库用户

为每个数据库创建两个标准用户：

```sql
-- 只读用户
CREATE USER 'test_db_readonly'@'%' IDENTIFIED BY 'readonly_pass';
GRANT SELECT_PRIV ON test_db.* TO 'test_db_readonly'@'%';

-- 读写用户
CREATE USER 'test_db_readwrite'@'%' IDENTIFIED BY 'readwrite_pass';
GRANT SELECT_PRIV, LOAD_PRIV, ALTER_PRIV ON test_db.* TO 'test_db_readwrite'@'%';
```

### 步骤3: 配置Doris数据库用户

```sql
INSERT INTO doris_database_users (
    cluster_id, 
    database_name, 
    readonly_username, 
    readonly_password, 
    readwrite_username, 
    readwrite_password
) VALUES (
    1,                      -- 集群ID
    'test_db',              -- 数据库名
    'test_db_readonly',     -- 只读用户名
    'readonly_pass',        -- 只读密码
    'test_db_readwrite',    -- 读写用户名
    'readwrite_pass'        -- 读写密码
);
```

### 步骤4: 创建平台用户

```sql
INSERT INTO platform_users (id, oauth_user_id, username, email)
VALUES ('user001', 'oauth_123', 'zhangsan', 'zhangsan@example.com');
```

### 步骤5: 分配权限

#### 方式1: 使用API（推荐）

```bash
# 授予只读权限
curl -X POST http://localhost:8080/v1/permissions/grant \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user001",
    "clusterId": 1,
    "databaseName": "test_db",
    "permissionLevel": "readonly",
    "grantedBy": "admin"
  }'

# 授予读写权限
curl -X POST http://localhost:8080/v1/permissions/grant \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user001",
    "clusterId": 1,
    "databaseName": "test_db",
    "permissionLevel": "readwrite",
    "grantedBy": "admin"
  }'
```

#### 方式2: 直接插入数据库

```sql
INSERT INTO user_database_permissions (
    user_id, 
    cluster_id, 
    database_name, 
    permission_level, 
    granted_by
) VALUES (
    'user001',      -- 用户ID
    1,              -- 集群ID
    'test_db',      -- 数据库名
    'readonly',     -- 权限级别: readonly 或 readwrite
    'admin'         -- 授权人
);
```

### 步骤6: 测试权限

#### 前端请求示例

```javascript
// 在HTTP请求中添加用户头
fetch('http://localhost:8080/v1/doris-clusters/1/databases', {
  headers: {
    'X-User-Id': 'user001',
    'X-Username': 'zhangsan',
    'X-OAuth-User-Id': 'oauth_123'
  }
})
.then(response => response.json())
.then(data => console.log(data));
```

#### cURL测试

```bash
# 查询数据库列表（需要权限）
curl -X GET http://localhost:8080/v1/doris-clusters/1/databases \
  -H "X-User-Id: user001" \
  -H "X-Username: zhangsan" \
  -H "X-OAuth-User-Id: oauth_123"

# 执行SQL查询（需要权限）
curl -X POST http://localhost:8080/v1/data-query/execute \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user001" \
  -H "X-Username: zhangsan" \
  -d '{
    "clusterId": 1,
    "database": "test_db",
    "sql": "SELECT * FROM test_table LIMIT 10"
  }'
```

## 📋 权限管理API

### 授予权限

```bash
POST /v1/permissions/grant
{
  "userId": "user001",
  "clusterId": 1,
  "databaseName": "test_db",
  "permissionLevel": "readonly",  // 或 "readwrite"
  "grantedBy": "admin"
}
```

### 批量授予权限

```bash
POST /v1/permissions/grant/batch
{
  "userId": "user001",
  "clusterId": 1,
  "databaseNames": ["db1", "db2", "db3"],
  "permissionLevel": "readonly",
  "grantedBy": "admin"
}
```

### 撤销权限

```bash
DELETE /v1/permissions/revoke?userId=user001&clusterId=1&databaseName=test_db
```

### 批量撤销权限

```bash
DELETE /v1/permissions/revoke/batch?userId=user001&clusterId=1&databaseNames=db1,db2,db3
```

### 查询用户权限

```bash
GET /v1/permissions/user/user001?clusterId=1
```

### 查询数据库权限

```bash
GET /v1/permissions/database/test_db?clusterId=1
```

## 🔍 常见问题

### Q1: 用户访问数据库时提示"用户没有访问数据库的权限"

**原因**: 用户未被授予该数据库的访问权限

**解决**: 
```sql
-- 检查用户权限
SELECT * FROM user_database_permissions WHERE user_id = 'user001';

-- 授予权限
INSERT INTO user_database_permissions (user_id, cluster_id, database_name, permission_level, granted_by)
VALUES ('user001', 1, 'test_db', 'readonly', 'admin');
```

### Q2: 提示"数据库的Doris用户配置不存在"

**原因**: 未配置该数据库的Doris用户

**解决**:
```sql
-- 检查配置
SELECT * FROM doris_database_users WHERE database_name = 'test_db';

-- 添加配置
INSERT INTO doris_database_users (cluster_id, database_name, readonly_username, readonly_password, readwrite_username, readwrite_password)
VALUES (1, 'test_db', 'test_db_readonly', 'pass', 'test_db_readwrite', 'pass');
```

### Q3: 请求返回401或403错误

**原因**: 请求头中缺少用户信息或用户未认证

**解决**: 确保请求包含以下头部
```
X-User-Id: user001
X-Username: zhangsan
X-OAuth-User-Id: oauth_123
```

### Q4: 如何查看用户的查询历史？

用户只能看到自己的查询历史：
```bash
GET /v1/data-query/history
Headers:
  X-User-Id: user001
```

### Q5: 如何查看用户的工作流？

用户只能看到自己创建的工作流：
```bash
GET /v1/tasks
Headers:
  X-User-Id: user001
```

## 🎯 权限级别说明

| 权限级别 | Doris用户 | 可执行操作 |
|---------|----------|-----------|
| readonly | xxx_readonly | SELECT, SHOW, DESCRIBE, EXPLAIN |
| readwrite | xxx_readwrite | SELECT, INSERT, UPDATE, DELETE, LOAD, ALTER |

## 🔐 安全建议

1. **密码管理**: Doris用户密码应使用强密码，定期更换
2. **权限最小化**: 只授予用户必需的最小权限
3. **审计日志**: 定期检查查询历史，监控异常访问
4. **权限过期**: 为临时权限设置过期时间
5. **OAuth集成**: 确保OAuth令牌验证正确实施

## 📞 技术支持

如有问题，请查看：
- 完整文档: `RBAC_IMPLEMENTATION_COMPLETE.md`
- 实施进度: `RBAC_IMPLEMENTATION_PROGRESS.md`
- 设计文档: `../docs/design/2025-12-12-data-platform-rbac-design.md`
