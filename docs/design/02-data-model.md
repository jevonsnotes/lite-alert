# 02 · 数据模型与文件存储

## 1. 文件布局

```
/data
├── meta.json                 # 数据版本号、初始化时间，启动时校验
├── users.json                # 全部用户(含管理员)
├── namespaces.json           # 全局命名空间索引(保唯一性)
├── topics/
│   ├── {namespaceId}.json    # 按命名空间分片，单文件不易过大
│   └── ...
├── apikeys/
│   └── {userId}.json         # 用户的 ApiKey 列表(每用户一文件，详见文档 11)
├── contacts/
│   └── {userId}.json         # 用户的通知目标列表(每用户一文件)
├── subscriptions/
│   └── {topicId}.json        # Topic → contactId[] 订阅关系
└── audit/
    └── 2026-06.log           # 审计日志，按月切分
```

> 设计要点：**索引**类(users/namespaces) 单文件加载，**明细**类按业务键分片，避免单文件膨胀。

## 2. 加密策略（字段级 Jasypt）

- **整体格式**：仍是明文 JSON，便于人眼审阅与故障排查
- **敏感字段**：在序列化前用 Jasypt 加密；反序列化时解密
- 标记方式：在 DTO 字段上加 `@Encrypted` 注解，由统一的 Jackson `BeanSerializerModifier` 处理

### 加密字段清单

| 实体 | 字段 | 说明 |
| --- | --- | --- |
| User | `passwordHash` | BCrypt 后再 Jasypt 包一层 |
| NotifyTarget | `endpoint` | 邮箱地址或 webhook URL |
| NotifyTarget | `secret` | 通道加签密钥（如 DingTalk） |
| MailServerConfig | `password` | SMTP 密码 |

> ApiKey 不在此表中：它**不存原文也不存可逆密文**，只存 HMAC-SHA-256 哈希值（pepper 来自环境变量）。详见文档 11。

### 主密钥来源

- `JASYPT_ENCRYPTOR_PASSWORD` 环境变量注入（docker-compose 中通过 `.env` 提供）
- application.yml 内不再回填该密钥
- 启动时若环境变量缺失，应用 fail-fast 拒绝启动

## 3. 实体定义

### 3.1 User
```json
{
  "id": "u_xxxx",
  "username": "alice",
  "passwordHash": "<encrypted>",
  "role": "ADMIN | USER",
  "enabled": true,
  "createdAt": "2026-06-15T10:00:00Z",
  "createdBy": "u_admin"
}
```

### 3.2 Namespace
```json
{
  "id": "ns_xxxx",
  "name": "order_service",       // 全局唯一，正则 ^[a-zA-Z][a-zA-Z0-9_-]{2,31}$
  "ownerId": "u_xxxx",
  "description": "订单服务",
  "createdAt": "..."
}
```

唯一性约束：`namespaces.json` 内 `name` 唯一；写入前在写锁内做去重检查。

### 3.3 Topic
```json
{
  "id": "t_xxxx",
  "namespaceId": "ns_xxxx",
  "name": "order_paid",          // 命名空间内唯一
  "status": "DRAFT | PUBLISHED | DISABLED",
  "auth": {
    "mode": "API_KEY",            // API_KEY | NONE，详见文档 04 §7
    "ipWhitelist": [],            // CIDR 列表，对所有模式生效
    "rateLimit": {                 // 不填走全局默认
      "perMinute": null,
      "perIp": null
    }
  },
  // 注：调用方鉴权统一用独立的 ApiKey 模块（见文档 11），Topic 自身不再保存 webhookKey
  "inboundFormat": {             // 入站报文 JSON Schema(Draft 2020-12)
    "type": "object",
    "required": ["orderId", "amount"],
    "properties": { ... }
  },
  "transform": {                 // 可选：报文转换规则，见文档 05
    "enabled": true,
    "mappings": [                // 字段映射列表
      { "from": "$.orderId",   "to": "orderId", "type": "string", "required": true },
      { "from": "$.amount",    "to": "amount",  "type": "number" },
      { "from": "$.user.name", "to": "buyer",   "type": "string", "default": "匿名" }
    ]
  },
  "notifyTemplate": {            // 通知渲染模板（Mustache），字符串拼接发生在这里
    "subject": "订单 {{orderId}} 已支付（{{buyer}}）",
    "body": "..."                // 支持 HTML
  },
  "createdAt": "...",
  "publishedAt": "..."
}
```

### 3.4 NotifyTarget （通知目标）

支持邮件 / 钉钉 / 飞书 / 企业微信群机器人。文件路径仍是 `contacts/{userId}.json`
（兼容老数据）；缺失 `type` 的旧记录加载时回填为 `EMAIL`。详见文档 06。

```json
{
  "id": "c_xxxx",
  "userId": "u_xxxx",
  "type": "EMAIL",                 // EMAIL | DINGTALK | FEISHU | WECOM
  "label": "运维群组",
  "endpoint": "<encrypted>",       // 邮箱地址或 webhook URL
  "secret": "<encrypted>",         // 可选；DingTalk 加签密钥等
  "enabled": true,
  "createdAt": "..."
}
```

### 3.5 Subscription（Topic ↔ NotifyTarget）
```json
// subscriptions/{topicId}.json
{
  "topicId": "t_xxxx",
  "contactIds": ["c_xxxx", "c_yyyy"],   // 字段名沿用历史，元素是 NotifyTarget id
  "updatedAt": "..."
}
```

## 4. 文件读写规约

- **原子写**：写 `xxx.json.tmp` → fsync → `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`
- **加锁**：每类文件一把 `ReentrantReadWriteLock`（按文件名）
- **索引**：启动时一次性加载到 Caffeine（无淘汰），写时同步更新内存与磁盘
- **校验**：每个 JSON 头部带 `_schemaVersion`，未来升级时执行迁移脚本
- **备份**：写入前若已存在文件，旁路保留 `xxx.json.bak`（仅保留一份）

## 5. 容量估算

| 实体 | 单条 ~Bytes | 1k 条占用 | 10k 条占用 |
| --- | --- | --- | --- |
| User | 300 | 300 KB | 3 MB |
| Topic | 2 KB | 2 MB | 20 MB |
| Contact | 250 | 250 KB | 2.5 MB |

结论：单实例 1 万级 Topic、5 万级 Contact 完全可承载。
