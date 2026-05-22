# 元数据 API

## 数据表

### 查询数据表列表

```
GET /api/dataTable/page
```

**参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| pageNum | int | 页码 |
| pageSize | int | 每页条数 |
| tableName | string | 表名模糊搜索 |
| layer | string | 数据层级筛选 |

### 创建数据表

```
POST /api/dataTable
```

**请求体**:

```json
{
  "tableName": "ods_user_info",
  "tableComment": "用户信息表",
  "layer": "ODS",
  "domainId": 1,
  "datasourceId": 1
}
```

### 获取数据表详情

```
GET /api/dataTable/{id}
```

### 更新数据表

```
PUT /api/dataTable/{id}
```

### 删除数据表

```
DELETE /api/dataTable/{id}
```

## 字段管理

### 查询表字段

```
GET /api/dataField/list?tableId={tableId}
```

### 批量保存字段

```
POST /api/dataField/batch
```

**请求体**:

```json
{
  "tableId": 1,
  "fields": [
    {
      "fieldName": "id",
      "fieldType": "BIGINT",
      "fieldComment": "主键",
      "isPrimaryKey": true
    }
  ]
}
```
