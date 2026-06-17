# 04 · 命名空间与 Topic

## 1. 命名空间（Namespace）

### 1.1 命名规则

正则：`^[a-zA-Z][a-zA-Z0-9_-]{2,31}$`
- 必须以英文字母开头
- 仅允许英文、数字、下划线 `_`、横杠 `-`
- 长度 3–32

### 1.2 唯一性

- 全局唯一（**所有用户下唯一**，不区分 owner）
- 写入前在 `namespaces.json` 写锁内做 `existsByName` 检查

### 1.3 操作

| 操作 | 接口 | 说明 |
| --- | --- | --- |
| 创建 | `POST /api/namespaces` | owner=当前用户 |
| 列表 | `GET /api/namespaces` | USER 只看自己；ADMIN 全部 |
| 编辑描述 | `PATCH /api/namespaces/{id}` | name 不可改，避免破坏 webhook URL |
| 删除 | `DELETE /api/namespaces/{id}` | 级联：拒绝删除存在 PUBLISHED Topic 的命名空间 |

## 2. Topic 状态机

```
   ┌──────────┐  publish   ┌────────────┐  disable  ┌──────────┐
   │  DRAFT   │ ─────────▶ │ PUBLISHED  │ ───────▶  │ DISABLED │
   └────┬─────┘            └─────┬──────┘            └─────┬────┘
        │ edit                    │ edit(限字段)             │ enable
        ▼                          ▼                          ▼
   字段全部可改               仅可改 notifyTemplate /          重新进入 PUBLISHED
                              transform / 订阅关系
```

- DRAFT：可编辑全部字段，webhook 接口返回 404
- PUBLISHED：webhook 接口生效；inboundFormat 不可改（避免破坏调用方）
- DISABLED：webhook 接口返回 423，不再投递；保留配置

> 若用户确实需要修改 `inboundFormat`：必须先 DISABLE → 再编辑 → 再 PUBLISH。前端给出明确提示，记录版本号。

## 3. Topic 字段一览

| 字段 | DRAFT 可改 | PUBLISHED 可改 | 说明 |
| --- | --- | --- | --- |
| name | ✅ | ❌ | 命名空间内唯一，规则同命名空间 |
| inboundFormat (JSON Schema) | ✅ | ❌ | 调用契约 |
| transform | ✅ | ✅ | 业务侧调整无破坏性 |
| notifyTemplate | ✅ | ✅ | 仅影响渲染 |
| auth.mode | ✅ | ✅ | `API_KEY`(默认) / `NONE`(免认证)，详见 §7 |
| auth.ipWhitelist | ✅ | ✅ | CIDR 列表，对所有模式生效；NONE 模式强烈建议配置 |
| auth.rateLimit | ✅ | ✅ | NONE 模式默认更严，详见文档 06 |
| 订阅目标 | ✅ | ✅ | 通过 Subscription，元素是任意类型的 NotifyTarget |

> 调用方鉴权凭证不再属于 Topic：所有 ApiKey 在独立的「ApiKey 管理」页面集中维护，通过 scope 关联到 Topic 或命名空间，详见文档 11。

## 4. 报文格式（inboundFormat）

- 采用 JSON Schema (Draft 2020-12)
- 前端提供两种编辑方式：
  - **可视化编辑器**：表单式增删字段、类型、必填、示例值
  - **源码编辑器**：直接写 schema，用 monaco editor
- 后端使用 `networknt/json-schema-validator` 校验
- 校验失败响应：
  ```json
  {
    "code": "INVALID_PAYLOAD",
    "errors": [
      {"path": "$.amount", "message": "must be number"}
    ]
  }
  ```

## 5. Webhook URL 与发布

- 形如 `POST /api/webhook/{namespace}/{topic}`
- 调用方鉴权由 `auth.mode` 决定（详见 §7）
- 发布动作（`POST /api/topics/{id}:publish`）会：
  1. 校验 inboundFormat 至少一个 sample 通过
  2. 校验是否绑定至少 1 个邮箱（无邮箱可发布但给出提醒）
  3. 若 `auth.mode=NONE`，二次确认并强提示风险
  4. 若 `auth.mode=API_KEY`，**不强制**用户已经创建了带本 Topic scope 的 ApiKey（允许先发布再分发凭证），但前端给一条「尚无 ApiKey 可调用此 Topic」的橙色提示
  5. 设置 status = PUBLISHED，记录 publishedAt
  6. 重新预热内存索引

## 6. 调试

- 「发送测试报文」按钮：在前端直接 POST 一个用户输入的 JSON 到 webhook
  - API_KEY 模式：下拉框选用一个当前用户名下、scope 已覆盖此 Topic 的 ApiKey，浏览器侧拉取明文（不缓存）
  - NONE 模式：直接发送
- 「调用记录」：从审计日志读取最近 100 条，展示 traceId / 所用 ApiKey id / 校验结果 / 通知结果

## 7. 调用方鉴权模式（auth.mode）

支持两种模式，由 Topic 创建者自助选择：

### 7.1 `API_KEY`（默认，推荐）
- 调用方必须在 Header 携带 `Authorization: Bearer <apiKey>`
- 凭证来自独立的 ApiKey 模块（详见文档 11），与 Topic 解耦：
  - 用户先在「ApiKey 管理」页创建 ApiKey，配置生效/失效时间与 scope
  - scope 内含本 Topic（直接选 Topic，或选所属命名空间）的 ApiKey 才能调用
- ApiKey 服务端只存哈希，仅在创建时一次性展示完整原文，丢失只能新建
- 服务端比较使用常量时间比较

### 7.2 `NONE`（免认证 / 公开 Topic）
- 调用方无需任何 Header 即可调用，适用于：
  - 内网监控告警源（已通过网络层隔离）
  - GitHub / GitLab 等无法自定义 Header 的回调
  - 临时演示与测试
- 风险护栏（默认全部启用）：
  | 护栏 | 默认值 | 说明 |
  | --- | --- | --- |
  | 二次确认 | 必选 | 发布时弹窗：「此 Topic 将公开可调用，强烈建议配合 IP 白名单」 |
  | IP 白名单 | 可选但建议必填 | 命中即放行，未命中直接 401（即便 mode=NONE）|
  | 限流 | 30 次/分钟、单 IP 10 次/分钟 | 比 API_KEY 模式更严，可在 `auth.rateLimit` 调整 |
  | 报文大小 | 32 KB | NONE 模式下默认更小，防 DoS |
  | audit 标记 | 强制记录 `authMode=NONE` + remoteIp | 便于事后追溯 |
- 创建权限：默认仅 `ADMIN` 可创建 `NONE` 模式 Topic；普通 USER 想用需在「系统设置」里由 ADMIN 开关 `lite-alert.webhook.allow-user-public-topic=true`
- 切换：`auth.mode` 可在线切换；切换为 `API_KEY` 时立刻要求所有调用方携带合法凭证
