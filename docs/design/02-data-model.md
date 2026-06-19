# 02 · 数据模型与存储

## 1. 存储策略

Lite-Alert 当前以**数据库持久化**为准：

- 默认：H2 文件数据库，路径由 `LITE_ALERT_DATA_DIR` 与 `LITE_ALERT_DATASOURCE_URL` 决定。
- 生产：可切换 MySQL / PostgreSQL。
- 兼容数据库：GaussDB 使用 PostgreSQL 协议与迁移脚本；OceanBase 使用 MySQL 协议与迁移脚本。
- 结构迁移：Flyway 根据 `LITE_ALERT_DATABASE_TYPE` 加载 `classpath:db/migration/{type}`。

`common.storage.FileStore` 仍保留，用于辅助文件、兼容迁移、导入导出等场景；核心业务数据不再依赖纯 JSON 文件作为唯一来源。

## 2. 数据库配置

`application.yml` 关键配置：

```yaml
spring:
  datasource:
    url: ${LITE_ALERT_DATASOURCE_URL:jdbc:h2:file:${LITE_ALERT_DATA_DIR:./data}/lite-alert;MODE=MySQL;AUTO_SERVER=TRUE}
    username: ${LITE_ALERT_DATASOURCE_USERNAME:sa}
    password: ${LITE_ALERT_DATASOURCE_PASSWORD:}
    driver-class-name: ${LITE_ALERT_DATASOURCE_DRIVER:org.h2.Driver}
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration/${LITE_ALERT_DATABASE_TYPE:h2}

lite-alert:
  database:
    type: ${LITE_ALERT_DATABASE_TYPE:h2}
```

## 3. 加密策略

- **配置敏感项**：通过 Jasypt `ENC(...)` 加密，主密钥来自 `JASYPT_ENCRYPTOR_PASSWORD`。
- **业务敏感字段**：使用 `@Encrypted` 标记，序列化/反序列化时由统一 Jackson 模块处理。
- **ApiKey**：不存原文，也不存可逆密文，只存 `HMAC-SHA-256(pepper, fullKey)`；pepper 来自 `LITE_ALERT_APIKEY_PEPPER`。

### 加密字段清单

| 实体 | 字段 | 说明 |
| --- | --- | --- |
| User | `passwordHash` | BCrypt 后再加密存储 |
| NotifyTarget | `endpoint` | 邮箱地址或 Webhook URL |
| NotifyTarget | `secret` | 通道加签密钥 |
| Mail 配置 | `password` | SMTP 密码 |
| ApiKey | `keyHash` | 不可逆 HMAC，不使用 Jasypt 解密 |

## 4. 核心实体

### 4.1 User

```json
{
  "id": "u_xxxx",
  "username": "alice",
  "passwordHash": "<encrypted bcrypt hash>",
  "role": "ADMIN | USER",
  "roleId": "r_xxxx",
  "enabled": true,
  "permissions": ["TOPIC_VIEW", "APIKEY_CREATE"],
  "createdAt": "2026-06-15T10:00:00Z",
  "updatedAt": "2026-06-15T10:00:00Z"
}
```

### 4.2 Role

```json
{
  "id": "r_xxxx",
  "name": "Ops",
  "description": "运维人员",
  "systemBuiltin": false,
  "permissions": ["DASHBOARD_VIEW", "TOPIC_VIEW", "DELIVERY_VIEW"],
  "createdAt": "...",
  "updatedAt": "..."
}
```

### 4.3 Namespace

```json
{
  "id": "ns_xxxx",
  "name": "order_service",
  "ownerId": "u_xxxx",
  "description": "订单服务",
  "enabled": true,
  "createdAt": "...",
  "updatedAt": "..."
}
```

命名空间名称全局唯一，规则见文档 04。

### 4.4 Topic

```json
{
  "id": "t_xxxx",
  "namespaceId": "ns_xxxx",
  "namespaceName": "order_service",
  "name": "order_paid",
  "description": "订单支付成功",
  "ownerId": "u_xxxx",
  "status": "DRAFT | PUBLISHED | DISABLED",
  "auth": {
    "mode": "API_KEY | NONE",
    "keyLocation": "HEADER | QUERY",
    "ipWhitelist": [],
    "rateLimit": { "perMinute": 60, "perIp": 10 }
  },
  "inboundFormat": { "type": "object", "properties": {} },
  "templates": {
    "EMAIL": { "subject": "...", "body": "..." },
    "WEBHOOK": {
      "outputFormat": "JSON | XML",
      "outputTemplate": {},
      "outputXmlTemplate": "<xml>...</xml>",
      "transform": { "enabled": true, "mappings": [] },
      "responseCheck": { "enabled": true, "successPath": "$.code", "successValue": "0" }
    }
  },
  "createdAt": "...",
  "updatedAt": "...",
  "publishedAt": "..."
}
```

旧字段 `transform` / `notifyTemplate` 仅作为兼容字段读取，保存时应迁移到 `templates`。

### 4.5 ApiKey

```json
{
  "id": "ak_xxxx",
  "ownerId": "u_xxxx",
  "name": "订单系统-生产",
  "prefix": "la_8f3a",
  "keyHash": "<HMAC-SHA-256>",
  "validFrom": "2026-06-15T00:00:00Z",
  "validUntil": null,
  "scopes": [
    { "type": "NAMESPACE", "id": "ns_aaaa" },
    { "type": "TOPIC", "id": "t_bbbb" }
  ],
  "status": "ACTIVE | REVOKED",
  "createdAt": "...",
  "lastUsedAt": "...",
  "usageCount": 1834,
  "rotateCount": 1,
  "rateLimitPerMinute": 120
}
```

### 4.6 NotifyTarget

```json
{
  "id": "c_xxxx",
  "userId": "u_xxxx",
  "label": "运维群组",
  "type": "EMAIL | DINGTALK | FEISHU | WECOM | WEBHOOK",
  "endpoint": "<encrypted>",
  "secret": "<encrypted>",
  "enabled": true,
  "createdAt": "..."
}
```

### 4.7 Subscription

```json
{
  "topicId": "t_xxxx",
  "contactIds": ["c_xxxx", "c_yyyy"],
  "updatedAt": "..."
}
```

字段名 `contactIds` 沿用历史，元素实际是 NotifyTarget id。

### 4.8 NotifyDelivery

```json
{
  "id": "d_xxxx",
  "traceId": "tr_xxxx",
  "topicId": "t_xxxx",
  "targetId": "c_xxxx",
  "channelType": "EMAIL",
  "status": "PENDING | SENDING | SUCCESS | FAILED | RETRYING",
  "attempts": 1,
  "nextRetryAt": "...",
  "errorMessage": "...",
  "createdAt": "...",
  "updatedAt": "..."
}
```

投递 payload 属于敏感信息，查询详情时需结合权限控制，默认脱敏展示。

## 5. 容量与边界

- H2 适合单机轻量使用和小规模生产；较高并发或多实例前置场景建议切换 MySQL / PostgreSQL。
- JSON Schema、templates、scopes 等复杂字段以 JSON 文本存储，避免不同数据库 JSON 方言差异。
- ApiKey prefix 用于快速定位元数据，最终仍必须用完整 key 重新计算 HMAC 并常量时间比较。
