# 06 · 通知渠道与目标

> 通知目标（NotifyTarget）是用户维护的投递目的地；通知渠道（NotifyChannel）是后端对不同目的地的发送策略。

## 1. 渠道抽象

当前接口签名：

```java
public interface NotifyChannel {
    NotifyTarget.Type type();

    void send(NotifyTarget target,
              Topic.ChannelTemplate template,
              String renderedSubject,
              String renderedBody,
              JsonNode payload,
              Map<String, String> systemVars) throws Exception;
}
```

设计含义：

- `target`：目标地址、加签密钥等配置。
- `template`：当前通道的专属模板配置。
- `renderedSubject` / `renderedBody`：已完成 Mustache 渲染的标题和正文。
- `payload`：原始入站 JSON，WEBHOOK 通道可用它生成出站请求体。
- `systemVars`：`namespace`、`topic`、`traceId`、`receivedAt`、`rawJson` 等系统变量。

## 2. 已实现渠道

| 类型 | 用途 | 实现要点 |
| --- | --- | --- |
| `EMAIL` | 邮件通知 | Spring `JavaMailSender`，支持 HTML body |
| `DINGTALK` | 钉钉群机器人 | Markdown 消息；`secret` 非空时启用 HMAC-SHA256 加签 |
| `FEISHU` | 飞书/Lark 机器人 | 卡片或 Markdown 类消息 |
| `WECOM` | 企业微信群机器人 | Markdown 消息，endpoint 通常包含机器人 key |
| `WEBHOOK` | 通用出站 HTTP | 支持 JSON/XML 出站模板、字段映射、响应断言 |

新增渠道只需新增 `NotifyChannel` 实现，并在 `NotifyTarget.Type` 中增加枚举值。

## 3. 通知目标（NotifyTarget）

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

字段说明：

| 字段 | 说明 |
| --- | --- |
| `endpoint` | 邮箱地址或 Webhook URL，敏感字段，加密存储 |
| `secret` | 可选密钥，例如钉钉加签 secret，加密存储 |
| `enabled` | false 时不投递 |
| `type` | 决定由哪个 NotifyChannel 处理 |

## 4. 通知目标接口

| 接口 | 说明 |
| --- | --- |
| `GET /api/contacts` | 当前用户全部目标 |
| `POST /api/contacts` | 添加目标，按 type 校验 endpoint 格式 |
| `PATCH /api/contacts/{id}` | 修改 label、enabled、endpoint、secret |
| `DELETE /api/contacts/{id}` | 删除目标，前端二次确认 |

API 路径继续使用历史名称 `contacts`，产品文案展示为“通知目标”。

## 5. Topic 订阅

| 接口 | 说明 |
| --- | --- |
| `GET /api/topics/{topicId}/subscription` | 获取 Topic 已订阅的目标 |
| `PUT /api/topics/{topicId}/subscription` | 覆盖保存目标 id 列表 |

约束：

- 订阅元素是 NotifyTarget id，字段名沿用历史 `contactIds`。
- 普通用户只能订阅自己名下目标。
- 一个 Topic 可同时订阅不同类型目标。

## 6. 模板渲染

通道模板保存在 Topic 的 `templates` 中：

```json
{
  "EMAIL": {
    "subject": "[{{namespace}}] {{topic}} {{title}}",
    "body": "<h3>{{title}}</h3><pre>{{rawJson}}</pre>"
  },
  "WECOM": {
    "subject": "{{title}}",
    "body": "### {{title}}\n> traceId: {{traceId}}"
  }
}
```

- EMAIL：subject 为邮件标题，body 可为 HTML。
- DINGTALK / FEISHU / WECOM：body 通常按 Markdown 或机器人消息格式组织。
- WEBHOOK：body 可作为原始模板；优先使用 `outputTemplate` / `outputXmlTemplate` 生成出站请求体。

## 7. 投递任务与重试

Lite-Alert 使用投递任务子系统代替纯内存重试队列：

```text
WebhookService
  └─ 创建 NotifyDelivery 记录（每个目标一条）
       └─ NotifyDeliveryWorker 后台扫描/领取任务
            ├─ 渲染当前目标类型模板
            ├─ 调用对应 NotifyChannel
            ├─ 成功：标记 SUCCESS
            └─ 失败：记录 error，按策略重试或标记 FAILED
```

相关模块：

| 模块 | 职责 |
| --- | --- |
| `NotifyDeliveryService` | 创建、查询、状态流转 |
| `NotifyDeliveryWorker` | 后台领取并执行投递 |
| `NotifyDeliveryStore` | 数据库存储 |
| `NotifyDeliveryJanitor` | 清理历史投递记录 |
| `NotifyDeliveryController` | `/api/deliveries` 查询接口 |

## 8. 限流与防滥用

- Webhook 入站限流由 Topic / ApiKey / IP 维度共同控制。
- ApiKey 可配置 `rateLimitPerMinute`。
- 下游目标暂不做单目标强限频，必要时可在 NotifyDeliveryService 增加目标级保护。
- 超限返回 429，并写审计。
