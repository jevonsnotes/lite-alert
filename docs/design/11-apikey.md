# 11 · ApiKey 管理

> 把调用方鉴权从 Topic 维度抽出来，做成独立的、可跨 Topic 复用的凭证体系。

## 1. 设计目标

| 目标 | 说明 |
| --- | --- |
| 一 key 多用 | 一个 ApiKey 可授权访问多个 Topic / 整个命名空间，避免每个 Topic 都给业务方发一份 key |
| 时效可控 | 支持生效时间、失效时间、永久有效；过期自动失效 |
| 最小权限 | 用户按 Topic 或命名空间精细授权，越权调用直接拒绝 |
| 可撤销 | 任意时刻可撤销，立刻生效 |
| 可审计 | 每次调用记录所用 ApiKey id，便于追溯泄露源 |
| 不可恢复 | 服务端不存原文 key，只存哈希；丢失只能重发，无法找回 |

## 2. 实体定义

`/data/apikeys/{userId}.json`：

```json
[
  {
    "id": "ak_xxxx",
    "ownerId": "u_xxxx",
    "name": "订单系统-生产",            // 用户自定义说明
    "prefix": "la_8f3a",               // key 前缀，明文，用于快速定位
    "keyHash": "<HMAC-SHA-256(key)>",  // 服务端只存哈希，不可逆
    "validFrom": "2026-06-15T00:00:00Z",
    "validUntil": null,                // null = 永久有效
    "scopes": [
      { "type": "NAMESPACE", "id": "ns_aaaa" },     // 整个命名空间
      { "type": "TOPIC",     "id": "t_bbbb" },      // 单个 Topic
      { "type": "TOPIC",     "id": "t_cccc" }
    ],
    "status": "ACTIVE | REVOKED",      // EXPIRED 由系统按 validUntil 推导，不落盘
    "createdAt": "2026-06-15T10:00:00Z",
    "lastUsedAt": "2026-06-15T11:23:45Z",
    "usageCount": 1834
  }
]
```

### 字段说明

- **prefix**：key 的前 8 字符（如 `la_8f3a`），明文存储，前端展示用，也是后端按 prefix 建立内存索引快速命中的依据
- **keyHash**：HMAC-SHA-256(serverPepper, fullKey)，serverPepper 来自环境变量 `LITE_ALERT_APIKEY_PEPPER`
  - 不用 Jasypt 加密：因为不需要解密，比对走 HMAC 重算
  - 即便磁盘文件泄露，也无法离线穷举（pepper 不在文件里）
- **scopes**：扁平列表，存在 NAMESPACE 项即覆盖该命名空间下所有 Topic（含未来新增的）
- **status**：仅 ACTIVE / REVOKED 两态；EXPIRED 是 `now() > validUntil` 的运行时推导
- **lastUsedAt / usageCount**：异步累计，每 30s 批量回写一次（避免高频 IO）

## 3. 生成流程

```
用户在前端点「新建 ApiKey」
  └─▶ POST /api/apikeys
        └─▶ 后端：
              1. 校验 name 合法、validUntil 在合理区间
              2. SecureRandom 32 字节 → base64 url-safe，得到 fullKey
              3. fullKey 拼前缀：finalKey = "la_" + fullKey  → 截前 8 字符作为 prefix
              4. keyHash = HMAC-SHA-256(pepper, finalKey)
              5. 落盘（仅存 prefix + keyHash + 元数据）
              6. **响应中一次性返回 finalKey 原文**
        └─▶ 前端弹窗：完整 key + 复制按钮 + 强提示「关闭后无法再次查看」
```

> 与 webhookKey 的差别：webhookKey 当时设计是「每次显示」走 Jasypt 解密，安全性较弱；ApiKey 直接学 GitHub PAT / AWS AK——**只展示一次**，丢了重发不找回。

## 4. 调用方鉴权流程

```
调用方：
  POST /api/webhook/{ns}/{topic}
  Authorization: Bearer la_8f3a............longstring

服务端：
  1. 解析 Authorization → 取出 finalKey
  2. 拆 prefix（前 8 字符）→ 在内存索引 prefixIndex.get(prefix) 取 ApiKey 元数据
        - 未命中 → 401
  3. 用同样的 pepper 算 HMAC(finalKey)，与 keyHash 常量时间比较
        - 不匹配 → 401（同 IP+prefix 连续 10 次失败 → 锁 IP 5 分钟）
  4. 校验 status=ACTIVE
  5. 校验时间窗口：validFrom ≤ now ≤ (validUntil ?? +∞)
        - 不在窗口 → 401，错误码 EXPIRED / NOT_YET_ACTIVE
  6. 校验授权：scopes 内存在以下任一即可
        - { type: "TOPIC", id: <当前 topic.id> }
        - { type: "NAMESPACE", id: <当前 topic.namespaceId> }
        - 否则 → 403 SCOPE_NOT_ALLOWED
  7. 校验 ApiKey.ownerId 是该 namespace 的归属（防止用户自己删了 ns 但 key 还能用的边界）
  8. 异步累计 lastUsedAt / usageCount
  9. audit 记录 apiKeyId（不记 finalKey）
```

每一步都用常量时间比较，且失败错误码区分清晰，便于业务方排查。

## 5. 内存索引

启动时把所有 `/data/apikeys/*.json` 加载到 Caffeine：

```
prefixIndex   : Map<String, ApiKeyMeta>           // O(1) 命中
ownerIndex    : Map<userId, List<ApiKeyMeta>>     // 用户列表页用
```

变更（新建 / 撤销 / 修改 scope / 改 validUntil）走写锁，先改文件再原子刷新两个索引。

## 6. 接口列表

| 接口 | 用途 |
| --- | --- |
| `GET /api/apikeys` | 当前用户全部 ApiKey（不含 keyHash，prefix 明文） |
| `POST /api/apikeys` | 新建，请求体 `{name, validFrom?, validUntil?, scopes[]}`，响应一次性返回 finalKey |
| `PATCH /api/apikeys/{id}` | 修改 name / validUntil / scopes（不可改 prefix / keyHash） |
| `POST /api/apikeys/{id}:revoke` | 撤销（status → REVOKED），立即生效，不可逆 |
| `DELETE /api/apikeys/{id}` | 物理删除，仅对 REVOKED 或 EXPIRED 的 key 允许（避免误删活跃 key） |
| `GET /api/apikeys/{id}/usage` | 最近调用记录（来自 audit），分页返回 |

### scopes 编辑校验

- 必须至少 1 项
- TOPIC / NAMESPACE 的 id 必须属于当前用户（防止勾选他人资源）
- 同一 NAMESPACE 已存在时，其下 TOPIC 项自动去重（保留 NAMESPACE）

### validity 校验

- `validFrom` 默认 = now；可前置但不允许超过 1 年前
- `validUntil`：
  - null → 永久有效，前端在弹窗强提示
  - 必须晚于 validFrom + 5 分钟
  - 上限可在系统设置里配置（默认 5 年）

## 7. 与 Topic 鉴权模式的关系

Topic 维度只保留两种 `auth.mode`：

| mode | 含义 | 是否需要 ApiKey |
| --- | --- | --- |
| `API_KEY`（默认） | 需要有效 ApiKey 且 scope 覆盖该 Topic | 是 |
| `NONE` | 公开 Topic，免认证 | 否（仅依赖 IP 白名单 + 限流） |

> 注意：旧设计里挂在 Topic 上的 `webhookKey` 字段**移除**，全部由 ApiKey 接管。

## 8. 前端「ApiKey 管理」页

入口：顶部菜单「ApiKey」（与「通知目标」并列）

### 列表

| 列 | 说明 |
| --- | --- |
| 名称 | 用户自定义 |
| Key 前缀 | `la_8f3a••••••••`，悬浮显示完整 prefix（不是完整 key） |
| 授权范围 | 标签气泡，前 3 个直显，剩余「+N」，点开看详情 |
| 有效期 | 「永久有效」/「至 2026-12-31」/「已过期」 |
| 状态 | ACTIVE / REVOKED / EXPIRED 三色标签 |
| 最近使用 | 相对时间，hover 显示绝对时间与 usageCount |
| 操作 | 编辑 / 撤销 / 删除（按状态置灰） |

### 新建对话框

- 名称（必填）
- 生效时间（datetime，默认现在）
- 失效时间（datetime + 「永久有效」复选框，二选一）
- 授权范围：
  - 「按命名空间」Tab：穿梭框，左边当前用户 namespace 列表，右边已选
  - 「按 Topic」Tab：树形组件，按 namespace 分组显示 Topic，多选
  - 同一对话框内可以混合勾选；前端实时校验冗余项
- 提交后：弹出**只展示一次**的完整 key 弹窗，含「复制」「我已保存」按钮，关闭后再无法查看

### 撤销

- 二次确认：「撤销后所有正在使用此 Key 的调用方将立刻 401，确定继续？」
- 撤销后状态变为 REVOKED，列表行置灰

## 9. 安全细节

- **永不日志**：finalKey 全程不进任何日志，pepper 也不进
- **复制保护**：前端复制按钮使用 `navigator.clipboard`，弹窗 30 秒后自动关闭
- **限频新建**：单用户每分钟最多新建 5 个 ApiKey，避免误操作
- **轮换提醒**：永久 key 在「ApiKey」页顶部 banner 提示「建议为 ApiKey 设置失效时间」
- **泄露应急**：「撤销」按钮一键失效；后续可加「按 IP 模式批量识别异常调用」

## 10. 数据迁移（如已有 webhookKey）

由于这是新项目，目前无需迁移。设计上保留一个空操作钩子 `ApiKeyMigrator`，未来若有人从旧 webhookKey 模型升级，可批量为每个 Topic 自动生成一个 scope=该 Topic 的 ApiKey。
