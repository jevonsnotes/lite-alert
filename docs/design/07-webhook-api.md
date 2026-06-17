# 07 · Webhook 接入接口

## 1. 接口定义

```
POST /api/webhook/{namespace}/{topic}
Headers:
  Authorization: Bearer <apiKey>  条件必填：仅 auth.mode=API_KEY 时必填
  X-Trace-Id: <optional>          可选，用于贯穿业务方与 lite-alert
  Content-Type: application/json
Body:
  <符合 topic.inboundFormat 的 JSON>
```

### 响应

| 状态 | 含义 |
| --- | --- |
| 202 Accepted | 已受理，已入派发队列。返回 `{traceId}` |
| 400 Bad Request | 报文格式不合法或 Schema 校验失败，返回错误明细 |
| 401 Unauthorized | ApiKey 缺失/无效/过期/已撤销，或 IP 不在白名单 |
| 403 Forbidden | ApiKey 有效但 scope 未覆盖此 Topic |
| 404 Not Found | namespace / topic 不存在或处于 DRAFT |
| 423 Locked | topic 已 DISABLED |
| 429 Too Many Requests | 限流触发 |
| 500 Internal Server Error | 服务端异常，含 traceId |

> 设计为 **fire-and-forget**：我们承诺已落审计、已入派发队列，不承诺投递成功。投递结果通过 audit/前端调用记录查询。

## 2. 鉴权细节

### 2.1 auth.mode = API_KEY（默认）

- 调用方 Header 必须携带 `Authorization: Bearer <apiKey>`
- ApiKey 来自独立的 ApiKey 模块，**与 Topic 解耦**：用户先在「ApiKey 管理」页创建凭证、勾选授权范围
- 调用时服务端按以下顺序校验（详见文档 11 §4）：
  1. 解析 Bearer token
  2. 取 prefix（前 8 字符）→ 内存索引命中 ApiKey 元数据；未命中 → 401
  3. HMAC-SHA-256(serverPepper, fullKey) 与 `keyHash` 常量时间比较；不匹配 → 401
  4. 校验 `status=ACTIVE`；REVOKED → 401
  5. 校验 `validFrom ≤ now ≤ validUntil`；不在窗口 → 401（`code=EXPIRED` 或 `NOT_YET_ACTIVE`）
  6. 校验 scope 覆盖：scopes 中存在 `{type:TOPIC, id:当前Topic}` 或 `{type:NAMESPACE, id:当前namespace}`；否则 → 403
  7. 失败计数：同 IP + 同 prefix 连续 10 次失败 → 该 IP 锁 5 分钟
- 错误响应区分清晰，便于业务方排查：
  ```json
  { "code": "API_KEY_EXPIRED",       "traceId": "tr_xxx", "message": "..." }
  { "code": "API_KEY_REVOKED",       "traceId": "tr_xxx", "message": "..." }
  { "code": "API_KEY_INVALID",       "traceId": "tr_xxx", "message": "..." }
  { "code": "SCOPE_NOT_ALLOWED",     "traceId": "tr_xxx", "message": "ApiKey 未授权访问此 Topic" }
  ```
- audit 日志记录 `apiKeyId`（不记 token 原文）

### 2.2 auth.mode = NONE（免认证 / 公开 Topic）

- 调用方**无需 Authorization 头**即可调用
- 即便如此，仍走以下校验：
  1. **IP 白名单**：若 Topic 配置了 `auth.ipWhitelist`，未命中直接 401（这是 NONE 模式下唯一的硬性身份判定）
  2. **限流**：默认 30 次/分钟、单 IP 10 次/分钟，可在 Topic 配置覆盖；超限 429
  3. **报文大小**：默认上限 32 KB（API_KEY 模式默认 64 KB）
  4. **Schema 校验**：与 API_KEY 模式一致
- audit 日志强制记录 `authMode=NONE` + remoteIp + userAgent
- 仅 ADMIN 默认可创建；普通 USER 需配置 `lite-alert.webhook.allow-user-public-topic=true` 才能选择

> 设计意图：让用户**自己**根据接入方能力决定鉴权强度。GitHub Webhook、企业内网巡检脚本等不便携带自定义 Header 的场景，可选 NONE + IP 白名单；其余默认走 API_KEY。

## 3. 报文校验链

```
原始 body
  └─ 大小限制 (默认 64 KB；NONE 模式默认 32 KB)
      └─ JSON 解析
          └─ Schema 校验 (inboundFormat)
              └─ 业务校验（如 namespace.enabled）
                  └─ 进入派发
```

任意一步失败：拒绝并返回结构化错误：

```json
{
  "code": "INVALID_PAYLOAD",
  "traceId": "tr_xxxx",
  "errors": [
    {"path": "$.amount", "message": "must be number, found string"}
  ]
}
```

## 4. 幂等性（可选）

- 如果调用方携带 `X-Idempotency-Key`，服务端会用 LRU(10000) 缓存 5 分钟去重
- 命中 → 返回上次的 traceId，幂等 200
- 缓存只在内存，重启失效

## 5. 调用方文档自动生成

- 「Topic 详情 - 接入文档」页：实时生成
  - cURL 示例：API_KEY 模式带 `Authorization: Bearer <选中的 ApiKey>`；NONE 模式不带
  - JSON Schema
  - 字段表（从 schema 提取）
  - 一份转换后的样例输出
- 提供「复制」按钮，方便分享给业务侧
- API_KEY 模式下顶部有 ApiKey 选择器：列出当前用户名下、scope 已覆盖此 Topic 的 ApiKey；选中后 cURL 实时刷新

## 6. 接口代码风格（Controller）

```java
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    @PostMapping("/{ns}/{topic}")
    public ResponseEntity<WebhookAck> receive(
            @PathVariable String ns,
            @PathVariable String topic,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Trace-Id",     required = false) String traceId,
            @RequestBody JsonNode body,
            HttpServletRequest req) {

        WebhookAck ack = webhookService.handle(
            new WebhookRequest(ns, topic, authorization, body, traceId, req.getRemoteAddr())
        );
        return ResponseEntity.accepted().body(ack);
    }
}
```

业务异常 → 由 `@ControllerAdvice` 统一翻译为标准错误体。
