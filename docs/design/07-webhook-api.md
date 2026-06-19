# 07 · Webhook 接入接口

## 1. 接口定义

```text
POST /api/webhook/{namespace}/{topic}

Headers（当 Topic 配置 keyLocation=HEADER，默认）:
  Authorization: Bearer <apiKey>    必填
  Content-Type: application/json

Query（当 Topic 配置 keyLocation=QUERY）:
  ?key=<apiKey>                     必填

Body:
  <符合 topic.inboundFormat 的 JSON>
```

> traceId 由服务端 `TraceIdFilter` 生成并贯穿日志、错误响应、审计与投递记录。调用方如需要自有业务流水号，建议放在业务报文字段中。

## 2. 响应

| 状态 | 含义 |
| --- | --- |
| 200 OK | 已受理，投递任务已创建。返回 `{accepted, traceId, deliveryCount}` |
| 400 Bad Request | 报文格式不合法、JSON Schema 校验失败或模板/转换参数不合法 |
| 401 Unauthorized | ApiKey 缺失、无效、过期、未生效、已撤销，或 IP 不在白名单 |
| 403 Forbidden | ApiKey 有效但 scope 未覆盖此 Topic |
| 404 Not Found | namespace / topic 不存在，或 Topic 尚未发布 |
| 423 Locked | Topic 已禁用 |
| 429 Too Many Requests | 限流触发 |
| 413 Payload Too Large | 报文超过大小限制（默认 64 KB） |
| 500 Internal Server Error | 服务端异常，响应中包含 traceId |

`200 OK` 只表示 Lite-Alert 已完成鉴权、校验并创建投递任务；下游邮件、机器人或出站 Webhook 是否成功，要通过投递记录或审计查询。

## 3. 鉴权细节

### 3.1 API_KEY 认证

标准接入方式是 ApiKey 认证。Topic 通过 `auth.keyLocation` 决定从哪里读取 key：

| keyLocation | 说明 |
| --- | --- |
| `HEADER`（默认） | `Authorization: Bearer <apiKey>` |
| `QUERY` | URL 参数 `?key=<apiKey>` |

URL 参数认证容易出现在浏览器历史、代理日志、网关日志中，仅建议在调用方无法自定义 Header 时使用。

### 3.2 调用流程

服务端按以下顺序处理：

1. 根据 `{namespace}/{topic}` 查找 Topic，要求已发布且命名空间可用。
2. 按 `keyLocation` 从 Header 或 Query 参数取 ApiKey；缺失 → 401。
3. 取 prefix → 命中 ApiKey 元数据；未命中 → 401。
4. 使用 `LITE_ALERT_APIKEY_PEPPER` 重新计算 HMAC，与 `keyHash` 常量时间比较；不匹配 → 401。
5. 校验 `status=ACTIVE`；撤销 → 401。
6. 校验 `validFrom ≤ now ≤ validUntil`；未生效或过期 → 401。
7. 校验 scope 覆盖当前 Topic 或当前 Namespace；不覆盖 → 403。
8. 校验 IP 白名单、Topic 限流和 ApiKey 限流。
9. JSON 解析与 `inboundFormat` Schema 校验。
10. 为每个订阅目标创建投递任务，记录审计，更新 ApiKey 使用统计。
11. 返回 200。

错误响应示例：

```json
{ "code": "API_KEY_EXPIRED", "traceId": "tr_xxx", "message": "ApiKey 已过期" }
{ "code": "API_KEY_REVOKED", "traceId": "tr_xxx", "message": "ApiKey 已撤销" }
{ "code": "API_KEY_INVALID", "traceId": "tr_xxx", "message": "ApiKey 无效" }
{ "code": "SCOPE_NOT_ALLOWED", "traceId": "tr_xxx", "message": "ApiKey 未授权访问此 Topic" }
```

审计只记录 `apiKeyId`、prefix、keyLocation 等元数据，不记录 ApiKey 原文。

## 4. 调用方示例

### 请求头模式（默认）

```bash
curl -X POST "http://host:8080/api/webhook/order/order_paid" \
  -H "Authorization: Bearer la_xxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","amount":99.5}'
```

### URL 参数模式

```bash
curl -X POST "http://host:8080/api/webhook/order/order_paid?key=la_xxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","amount":99.5}'
```

## 5. 报文校验链

```text
原始 body
  └─ 大小限制（默认 64 KB）
      └─ JSON 解析
          └─ JSON Schema 校验（topic.inboundFormat）
              └─ 创建 NotifyDelivery 任务
                  └─ 后台 worker 渲染模板并投递
```

## 6. 接入文档自动生成

Topic 详情页应根据当前配置自动生成：

- cURL 示例：根据 `keyLocation` 切换 Header / Query。
- JSON Schema 与字段说明。
- 模板变量说明。
- 覆盖当前 Topic 的 ApiKey 列表。
- Webhook 成功响应与常见错误码说明。

## 7. Controller 契约

```java
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    @PostMapping("/{namespace}/{topic}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String namespace,
            @PathVariable String topic,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "key", required = false) String queryKey,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {

        Map<String, Object> ack = webhookService.handle(
                namespace, topic, authorization, queryKey, body, clientIp(request));
        return ResponseEntity.ok(ack);
    }
}
```

业务异常由统一异常处理器转换为标准错误体。
