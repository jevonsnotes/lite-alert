# 06 · 通知渠道与目标

> M8.1 起，"邮箱簿"升级为"通知目标"，支持邮件 / 钉钉 / 飞书 / 企业微信
> 群机器人。文件路径与 API 路径都沿用历史 `contacts/`，让旧数据无缝迁移。

## 1. 渠道抽象

```java
public interface NotifyChannel {
    NotifyTarget.Type type();                // EMAIL | DINGTALK | FEISHU | WECOM
    void send(NotifyTarget target, String subject, String body) throws Exception;
}
```

- 每个 `Type` 一个 `NotifyChannel` 实现，由 Spring 自动收集 → `NotifyChannelRegistry`
- 调用时按 `target.type` 分发，找不到就跳过 + 记 audit
- 新增渠道（如 Slack / SMS）= 新加一个 `NotifyChannel` bean + 在 `NotifyTarget.Type`
  里加一个枚举值，无需改 dispatcher

### 已实现渠道

| 渠道 | 实现要点 |
| --- | --- |
| EMAIL | Spring `JavaMailSender`，HTML body |
| DINGTALK | Markdown 卡片；`secret` 非空时启用加签模式（HMAC-SHA256） |
| FEISHU | `interactive` card；不支持加签（建议 bot 侧用关键词模式） |
| WECOM | Markdown 消息；endpoint 是带 `key` 的完整 URL |

## 2. 通知目标（NotifyTarget）

### 2.1 数据归属

- 每个用户独立维护自己的目标列表
- 文件：`/data/contacts/{userId}.json`（沿用旧路径以兼容历史数据）
- 字段：`id / type / label / endpoint(加密) / secret(加密) / enabled / createdAt`
- 缺失 `type` 的旧记录加载时回填为 `EMAIL`

### 2.2 操作

| 接口 | 说明 |
| --- | --- |
| `GET /api/contacts` | 当前用户全部目标 |
| `POST /api/contacts` | 添加；按 type 校验 endpoint 格式（邮箱正则 / http(s) URL） |
| `PATCH /api/contacts/{id}` | 修改 label / enabled / endpoint / secret |
| `DELETE /api/contacts/{id}` | 删除前要求确认（前端二次提示） |

### 2.3 可达性测试

- 接口：`POST /api/contacts/{id}/test`（M8 后续可加；当前在 ADMIN 系统设置里有 SMTP 测试）
- 行为：使用固定模板向该目标发一条测试消息，结果写 audit
- 限频：每个目标每分钟最多 1 次

## 3. Topic 订阅

- 数据：`/data/subscriptions/{topicId}.json`
- 前端体验：Topic 详情「订阅」Tab 列出当前用户的全部目标，复选框勾选保存
- 校验：仅允许订阅 Topic 所有者名下的目标（避免越权）
- 写入：每次保存覆盖整个 `contactIds` 列表（字段名沿用历史，元素是 NotifyTarget id）

## 4. SMTP 配置

`application.yml`：

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 465
    username: notice@example.com
    password: ENC(xxxxxxxx)
    properties:
      mail.smtp.auth: true
      mail.smtp.ssl.enable: true
      mail.smtp.connectiontimeout: 5000
      mail.smtp.timeout: 10000
```

> 钉钉 / 飞书 / 企业微信不需要在 yml 里配，每个 `NotifyTarget` 自带 webhook URL。

## 5. 模板渲染

### 5.1 模板字段

```json
{
  "subject": "[{{namespace}}] {{topic}} {{title}}",
  "body": "<h3>{{title}}</h3><p>金额：{{amount}}</p><p>买家：{{buyer}}</p><pre>{{rawJson}}</pre>"
}
```

- 使用 Mustache 渲染（不允许逻辑表达式，安全）
- 上下文 = 转换后报文的所有字段 ∪ 内置变量（`namespace` / `topic` / `traceId` / `receivedAt` / `rawJson`）
- 字符串拼接（"订单 12345 已支付"）**只在这里发生** —— 转换层不做拼接（详见文档 05）

### 5.2 渠道差异

| 渠道 | subject | body |
| --- | --- | --- |
| EMAIL | 邮件标题 | HTML 邮件正文（直接用） |
| DINGTALK | 卡片标题 | 作为 Markdown 文本嵌入卡片 |
| FEISHU | 卡片 header | 作为 lark_md 文本嵌入卡片 |
| WECOM | （未使用） | 作为 Markdown 内容发送，subject 拼到正文头部 |

> body 字段对邮件是 HTML、对 chat-bot 是 Markdown，看起来"应该不一样"。
> 当前折中：用户写一份 HTML，chat-bot 渠道里 HTML 标签会被 Markdown
> 大部分忽略但不会报错；后续可加"渠道专属模板"。

### 5.3 渲染失败

- subject 渲染失败 → 退回 `[lite-alert] {{topic}}` 兜底
- body 渲染失败 → 退化为"原始 JSON" 块，保证用户至少能看到内容
- audit 标记 `notifyTemplateError=true`

## 6. 派发与重试

```
NotifyDispatcher
  ├─ 异步线程池 (notifyExecutor: core 4 / max 16 / queue 1000)
  ├─ 每个目标一个独立任务（避免互相影响）
  ├─ 失败 → RetryQueue (内存 DelayQueue + audit)
  └─ 单线程 worker 消费延时队列，复用同一份已渲染的 subject/body
```

- 重试策略：1min → 5min → 30min → 2h → 6h，最多 5 次
- 终态：成功 / 永久失败，写 audit
- ApiKey 命中信息也会带在 audit 里，便于"哪条调用导致的失败"追溯

## 7. 限流与防滥用

- 单个 Topic：默认 60 次/分钟（NONE 模式 30 次/分钟，可在 Topic 配置覆盖）
- 单个目标：暂未做单目标限频；如有滥用可加目标级令牌桶
- 超限：webhook 调用直接返回 429，并在 audit 中记录 `webhook.rate_limited`
