# 11 · ApiKey 管理

> ApiKey 是外部调用方访问 Webhook 的凭证体系，与 Topic 解耦，可跨 Topic 或 Namespace 复用。

## 1. 设计目标

| 目标 | 说明 |
| --- | --- |
| 一 key 多用 | 一个 ApiKey 可授权访问多个 Topic 或整个命名空间 |
| 最小权限 | scope 精确到 Topic / Namespace，越权调用直接拒绝 |
| 时效可控 | 支持生效时间、失效时间、永久有效 |
| 可撤销 | 任意时刻撤销，立即失效 |
| 可轮换 | 支持重新生成 key 原文，服务端只展示一次 |
| 可限流 | 可为单个 ApiKey 设置每分钟调用限制 |
| 可审计 | 记录 lastUsedAt、usageCount 和调用审计 |
| 不可恢复 | 服务端只存 HMAC 哈希，不存原文，不可找回 |

## 2. 实体定义

```json
{
  "id": "ak_xxxx",
  "ownerId": "u_xxxx",
  "name": "订单系统-生产",
  "prefix": "la_8f3a",
  "keyHash": "<HMAC-SHA-256(pepper, fullKey)>",
  "validFrom": "2026-06-15T00:00:00Z",
  "validUntil": null,
  "scopes": [
    { "type": "NAMESPACE", "id": "ns_aaaa" },
    { "type": "TOPIC", "id": "t_bbbb" }
  ],
  "status": "ACTIVE | REVOKED",
  "createdAt": "2026-06-15T10:00:00Z",
  "lastUsedAt": "2026-06-15T11:23:45Z",
  "usageCount": 1834,
  "rotateCount": 1,
  "rateLimitPerMinute": 120
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `prefix` | key 前 8 字符，明文保存，用于展示与快速定位 |
| `keyHash` | `HMAC-SHA-256(pepper, fullKey)`，不可逆 |
| `scopes` | `TOPIC` 或 `NAMESPACE` 授权范围 |
| `status` | 存储 ACTIVE / REVOKED；EXPIRED 由时间窗口运行时推导 |
| `rotateCount` | 轮换次数 |
| `rateLimitPerMinute` | 单 key 每分钟限流；为空时走 Topic 或全局默认 |

## 3. 生成流程

```text
用户点击「新建 ApiKey」
  └─▶ POST /api/apikeys
        └─▶ 后端：
              1. 校验 name、有效期、scope、rateLimitPerMinute
              2. SecureRandom 生成 fullKey
              3. finalKey = "la_" + randomPart
              4. prefix = finalKey 前 8 字符
              5. keyHash = HMAC-SHA-256(pepper, finalKey)
              6. 保存 prefix + keyHash + 元数据
              7. 响应中一次性返回 finalKey
        └─▶ 前端弹窗展示完整 key，关闭后不可再次查看
```

## 4. 轮换流程

接口：`POST /api/apikeys/{id}/rotate`

语义：

1. 校验当前用户有权操作该 ApiKey。
2. 重新生成 fullKey。
3. 更新 prefix、keyHash、rotateCount、必要的更新时间。
4. 返回新 key 原文，仅本次响应可见。
5. 旧 key 立即不可用。

轮换适用于疑似泄露或周期性安全治理。前端必须提示：轮换后所有调用方需要替换为新 key。

## 5. 调用方鉴权流程

```text
调用方：
  POST /api/webhook/{namespace}/{topic}
  Authorization: Bearer la_8f3a............longstring

服务端：
  1. 根据 Topic 的 keyLocation 读取 Header 或 Query key
  2. 拆 prefix，在索引或存储中定位候选 ApiKey
  3. 用 pepper 计算 HMAC(fullKey)，与 keyHash 常量时间比较
  4. 校验 status=ACTIVE
  5. 校验 validFrom / validUntil 时间窗口
  6. 校验 scope 覆盖当前 Topic 或 Namespace
  7. 校验 ApiKey / Topic / IP 限流
  8. 更新 lastUsedAt、usageCount
  9. 审计记录 apiKeyId，不记录原文
```

## 6. 接口列表

| 接口 | 用途 |
| --- | --- |
| `GET /api/apikeys` | 当前用户全部 ApiKey（不含 keyHash 和原文） |
| `GET /api/apikeys/covering?topicId=` | 查询可覆盖某 Topic 的 ApiKey，用于接入文档和调试 |
| `POST /api/apikeys` | 新建，响应一次性返回 finalKey |
| `PATCH /api/apikeys/{id}` | 修改 name、validUntil、scopes、rateLimitPerMinute 等可变元数据 |
| `POST /api/apikeys/{id}/revoke` | 撤销，立即生效，不可逆 |
| `POST /api/apikeys/{id}/rotate` | 轮换，返回新 key 原文，仅一次 |
| `DELETE /api/apikeys/{id}` | 删除，通常仅允许删除已撤销或已过期 key |

## 7. scopes 校验

- 必须至少包含 1 项授权。
- TOPIC / NAMESPACE id 必须属于当前用户可管理范围。
- 已授权 NAMESPACE 时，其下 TOPIC 项可视为冗余。
- Webhook 调用时满足以下任一条件即可：
  - `{ type: "TOPIC", id: 当前 topic.id }`
  - `{ type: "NAMESPACE", id: 当前 topic.namespaceId }`

## 8. 有效期与状态

| 状态 | 来源 | 行为 |
| --- | --- | --- |
| ACTIVE | 存储字段 | 可用，但仍需通过时间窗口与 scope 校验 |
| REVOKED | 存储字段 | 永久不可用 |
| NOT_YET_ACTIVE | 运行时推导 | 当前时间早于 validFrom，返回 401 |
| EXPIRED | 运行时推导 | 当前时间晚于 validUntil，返回 401 |

`validUntil = null` 表示永久有效，前端应给予风险提示。

## 9. 前端 ApiKey 管理页

### 列表

| 列 | 说明 |
| --- | --- |
| 名称 | 用户自定义 |
| Key 前缀 | `la_8f3a••••••••`，不是完整 key |
| 授权范围 | Namespace / Topic 标签 |
| 有效期 | 永久有效 / 到期时间 / 已过期 |
| 状态 | ACTIVE / REVOKED / EXPIRED |
| 最近使用 | lastUsedAt 与 usageCount |
| 限流 | rateLimitPerMinute |
| 操作 | 编辑 / 轮换 / 撤销 / 删除 |

### 新建对话框

- 名称。
- 生效时间。
- 失效时间或永久有效。
- 授权范围。
- 可选限流。
- 提交后展示完整 key，并要求用户确认已保存。

### 轮换与撤销

- 轮换：提示调用方必须替换新 key。
- 撤销：提示所有使用该 key 的调用会立即 401。

## 10. 安全细节

- finalKey 全程不得进入日志、审计、异常信息。
- pepper 只通过环境变量提供，不写入配置仓库。
- Query 传 key 有泄露风险，默认推荐 Header。
- ApiKey 使用统计可以即时回写，低频场景优先保证审计准确；高频场景可再引入批量回写优化。
- 永久 key、长时间未使用 key、频繁失败 key 应在前端给予风险提醒。

## 11. 数据迁移

早期 Topic 级 webhookKey 已被独立 ApiKey 取代。若存在旧数据，迁移策略是为每个 Topic 生成一个 scope=该 Topic 的 ApiKey，并通知调用方替换接入凭证。
