# 07 · Webhook 接入接口

## 1. 接口定义

```
POST /api/webhook/{namespace}/{topic}
Query (当 Topic 配置 keyLocation=QUERY):
  ?key=<apiKey>                     必填
Headers (当 Topic 配置 keyLocation=HEADER，默认):
  Authorization: Bearer <apiKey>    必填
  X-Trace-Id: <optional>            可选，用于贯穿业务方与 lite-alert
  Content-Type: application/json
Body:
  <符合 topic.inboundFormat 的 JSON>
```

### 响应

| 状态 | 含义 |
| --- | --- |
| 200 OK | 已受理，投递任务已写入数据库。返回 `{accepted, traceId, deliveryCount}` |
| 400 Bad Request | 报文格式不合法或 Schema 校验失败，返回错误明细 |
| 401 Unauthorized | ApiKey 缺失/无效/过期/已撤销，或 IP 不在白名单 |
| 403 Forbidden | ApiKey 有效但 scope 未覆盖此 Topic |
| 404 Not Found | namespace / topic 不存在或处于 DRAFT |
| 423 Locked | topic 已 DISABLED |
| 429 Too Many Requests | 限流触发 |
| 413 Payload Too Large | 报文超过大小限制（默认 64 KB） |
| 500 Internal Server Error | 服务端异常，含 traceId |

> 设计为 **fire-and-forget**：我们承诺已落审计、已把投递任务持久化到数据库，不承诺投递成功。投递结果通过审计日志/前端调用记录查询。

## 2. 鉴权细节

### 2.1 统一 API_KEY 认证

所有 Topic 统一使用 ApiKey 认证，不再支持免认证（NONE）模式。

用户可在 Topic 的「安全与接入」Tab 中选择 ApiKey 传递方式：

| keyLocation | 说明 |
| --- | --- |
| `HEADER`（默认） | `Authorization: Bearer <apiKey>` |
| `QUERY` | URL 参数 `?key=<apiKey>` |

**注意**：URL 参数认证更容易出现在浏览器历史、代理日志、网关日志中。推荐优先使用请求头认证。

### 2.2 调用流程

调用方携带 ApiKey 调用 Webhook 时，服务端按以下顺序校验：

1. 根据 `keyLocation` 从 Header 或 Query 参数取 ApiKey；缺失 → 401
2. 取 prefix（前 8 字符）→ 内存索引命中 ApiKey 元数据；未命中 → 401
3. HMAC-SHA-256(serverPepper, fullKey) 与 `keyHash` 常量时间比较；不匹配 → 401
4. 校验 `status=ACTIVE`；REVOKED → 401
5. 校验 `validFrom ≤ now ≤ validUntil`；不在窗口 → 401（`code=EXPIRED` 或 `NOT_YET_ACTIVE`）
6. 校验 scope 覆盖：scopes 中存在 `{type:TOPIC, id:当前Topic}` 或 `{type:NAMESPACE, id:当前namespace}`；否则 → 403
7. 失败计数：同 IP + 同 prefix 连续 10 次失败 → 该 IP 锁 5 分钟

错误响应区分清晰，便于业务方排查：

```json
{ "code": "API_KEY_EXPIRED",       "traceId": "tr_xxx", "message": "..." }
{ "code": "API_KEY_REVOKED",       "traceId": "tr_xxx", "message": "..." }
{ "code": "API_KEY_INVALID",       "traceId": "tr_xxx", "message": "..." }
{ "code": "SCOPE_NOT_ALLOWED",     "traceId": "tr_xxx", "message": "ApiKey 未授权访问此 Topic" }
```

audit 日志记录 `apiKeyId`（不记 token 原文），并记录 `keyLocation` 值。

### 2.3 调用方示例

**请求头模式（默认）**：

```bash
curl -X POST "http://host/api/webhook/ns/topic" \
  -H "Authorization: Bearer la_xxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"title": "告警"}'
```

**URL 参数模式**：

```bash
curl -X POST "http://host/api/webhook/ns/topic?key=la_xxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"title": "告警"}'
```

## 3. 报文校验链

```
原始 body
  └─ 大小限制 (默认 64 KB)
      └─ JSON 解析
          └─ Schema 校验 (inboundFormat)
              └─ 业务校验（如 namespace.enabled）
                  └─ 写入投递任务 + 进入派发
```

## 4. 幂等性（可选）

- 如果调用方携带 `X-Idempotency-Key`，服务端会用 LRU(10000) 缓存 5 分钟去重
- 命中 → 返回上次的 traceId，幂等 200
- 缓存只在内存，重启失效

## 5. 调用方文档自动生成

- 「Topic 详情 - 接入文档」页：实时生成
  - cURL 示例：根据当前 Topic 的 `keyLocation` 自动切换 Header / URL 参数格式
  - JSON Schema
  - 字段表（从 schema 提取）
  - 一份转换后的样例输出
- 提供「复制」按钮，方便分享给业务侧
- 顶部有 ApiKey 选择器：列出当前用户名下、scope 已覆盖此 Topic 的 ApiKey；选中后 cURL 实时刷新

## 6. 接口代码风格（Controller）

```java
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    @PostMapping("/{ns}/{topic}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String ns,
            @PathVariable String topic,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "key", required = false) String queryKey,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest req) {

        Map<String, Object> ack = webhookService.handle(
                ns, topic, authorization, queryKey, body, clientIp(req));
        return ResponseEntity.ok(ack);
    }
}
```

业务异常 → 由 `@ControllerAdvice` 统一翻译为标准错误体。
