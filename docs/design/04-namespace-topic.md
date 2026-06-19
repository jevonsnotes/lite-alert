# 04 · 命名空间与 Topic

## 1. 命名空间（Namespace）

### 1.1 命名规则

正则：`^[a-zA-Z][a-zA-Z0-9_-]{2,31}$`

- 必须以英文字母开头。
- 仅允许英文、数字、下划线 `_`、横杠 `-`。
- 长度 3–32。

### 1.2 唯一性与归属

- 命名空间名称全局唯一。
- 每个命名空间有 `ownerId`，普通用户只能管理自己的命名空间。
- 管理员可查看全部命名空间，并可执行运维管理动作。

### 1.3 操作

| 操作 | 接口 | 说明 |
| --- | --- | --- |
| 创建 | `POST /api/namespaces` | owner=当前用户 |
| 列表 | `GET /api/namespaces` | USER 只看自己；ADMIN 可看全部 |
| 详情 | `GET /api/namespaces/{id}` | 返回命名空间及统计信息 |
| 编辑 | `PATCH /api/namespaces/{id}` | 通常只修改描述等非 URL 字段 |
| 禁用 | `POST /api/namespaces/{id}/disable` | 禁用后其下 Topic 不应再被调用 |
| 启用 | `POST /api/namespaces/{id}/enable` | 恢复可用 |
| 删除 | `DELETE /api/namespaces/{id}` | 删除前校验 Topic 与关联数据 |

## 2. Topic 状态机

```text
   ┌──────────┐  publish   ┌────────────┐  disable  ┌──────────┐
   │  DRAFT   │ ─────────▶ │ PUBLISHED  │ ───────▶  │ DISABLED │
   └────┬─────┘            └─────┬──────┘            └─────┬────┘
        │ edit                    │ edit(限字段)             │ enable
        ▼                          ▼                          ▼
   字段全部可改                模板/转换/订阅/安全参数        重新进入 PUBLISHED
```

- DRAFT：草稿态，Webhook 不对外生效。
- PUBLISHED：正式发布，Webhook 可调用。
- DISABLED：已禁用，Webhook 返回锁定/不可用类错误，不再创建投递任务。

## 3. Topic 字段一览

| 字段 | DRAFT 可改 | PUBLISHED 可改 | 说明 |
| --- | --- | --- | --- |
| `name` | ✅ | ❌ | 命名空间内唯一，影响 Webhook URL |
| `description` | ✅ | ✅ | 管理端展示说明 |
| `namespaceName` | 系统维护 | 系统维护 | 便于列表与调用记录展示 |
| `inboundFormat` | ✅ | 谨慎 | JSON Schema 调用契约，发布后修改可能影响调用方 |
| `auth.mode` | ✅ | ✅ | 当前代码保留 `API_KEY` / `NONE` 枚举；产品策略以 API_KEY 为默认与推荐 |
| `auth.keyLocation` | ✅ | ✅ | `HEADER` / `QUERY`，决定 ApiKey 从哪里读取 |
| `auth.ipWhitelist` | ✅ | ✅ | CIDR 或 IP 白名单 |
| `auth.rateLimit` | ✅ | ✅ | Topic 与 IP 级限流参数 |
| `templates` | ✅ | ✅ | 按通知通道维护模板与转换规则 |
| `订阅目标` | ✅ | ✅ | 通过 Subscription 维护 NotifyTarget 列表 |

## 4. 报文格式（inboundFormat）

- 采用 JSON Schema 校验入站 JSON 报文。
- 后端使用 `JsonSchemaService` 完成 Schema 校验。
- 校验失败时返回 400，并包含 traceId 与错误信息。
- Schema 是调用方契约，发布后修改需要谨慎处理。

示例：

```json
{
  "type": "object",
  "required": ["orderId", "amount"],
  "properties": {
    "orderId": { "type": "string" },
    "amount": { "type": "number" }
  }
}
```

## 5. 通道模板（templates）

Topic 使用 `templates[NotifyTarget.Type]` 保存通道专属配置。不同目标类型可以有不同 subject、body、转换规则或出站格式。

```json
{
  "templates": {
    "EMAIL": {
      "subject": "订单 {{orderId}} 已支付",
      "body": "<h3>{{orderId}}</h3><p>{{amount}}</p>"
    },
    "DINGTALK": {
      "subject": "订单通知",
      "body": "### 订单 {{orderId}} 已支付\n金额：{{amount}}"
    },
    "WEBHOOK": {
      "outputFormat": "JSON",
      "outputTemplate": {
        "msg_type": "order_paid",
        "traceId": "{{traceId}}"
      },
      "transform": {
        "enabled": true,
        "mappings": [
          { "from": "$.orderId", "to": "data.orderId", "type": "string", "required": true }
        ]
      },
      "responseCheck": {
        "enabled": true,
        "bodyType": "AUTO",
        "successPath": "$.code",
        "operator": "EQ",
        "successValue": "0"
      }
    }
  }
}
```

旧版顶层 `transform` / `notifyTemplate` 字段仅用于兼容旧数据，读取后应折叠到 EMAIL 或 WEBHOOK 对应模板。

## 6. Webhook URL 与发布

- URL：`POST /api/webhook/{namespace}/{topic}`。
- 调用方鉴权优先使用 `Authorization: Bearer <apiKey>`。
- 若 `keyLocation=QUERY`，调用方使用 `?key=<apiKey>`。
- 发布动作：`POST /api/topics/{id}/publish`。

发布前至少应校验：

1. Topic 名称、命名空间归属合法。
2. `inboundFormat` 是合法 JSON Schema。
3. 若目标通道需要模板，模板语法可被渲染。
4. API_KEY 模式下，提示用户需要创建覆盖该 Topic 或 Namespace 的 ApiKey。
5. 写入 `publishedAt` 与 `updatedAt`。

## 7. 调试与接入文档

Topic 详情页承担两类工作：

- **调试**：使用示例报文进行模板 dry-run，检查转换结果、输出模板和变量是否符合预期。
- **接入文档**：根据 `keyLocation` 自动生成 cURL 示例、展示 JSON Schema、ApiKey 选择器和调用说明。

## 8. 鉴权模式说明

代码层当前保留：

| mode | 说明 |
| --- | --- |
| `API_KEY` | 默认与推荐。调用方必须提供合法 ApiKey，且 scope 覆盖当前 Topic 或 Namespace |
| `NONE` | 兼容历史枚举，风险较高；如启用必须配合 IP 白名单和更严格限流 |

产品与文档主线以 API_KEY 作为标准接入方式，不再推荐公开免认证 Topic。
