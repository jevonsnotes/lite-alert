# 01 · 架构与数据流

## 1. 总体架构

```text
┌─────────────────────────────────────────────────────────────┐
│                        浏览器 (Vue3)                         │
│ 登录 / Dashboard / Namespace / Topic / ApiKey / 通知目标 / 管理 │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS / 同源 / JWT
┌──────────────────────────▼──────────────────────────────────┐
│                Spring Boot 3.5 (单 JAR)                      │
│ ┌─────────┐  ┌──────────┐  ┌────────────┐  ┌──────────────┐ │
│ │ Static  │  │ /api/**  │  │/api/webhook│  │ Scheduler    │ │
│ │ (Vue)   │  │ 管理接口  │  │ 调用方入口  │  │ 投递/清理任务 │ │
│ └─────────┘  └────┬─────┘  └─────┬──────┘  └──────┬───────┘ │
│                   │              │                 │         │
│                   ▼              ▼                 ▼         │
│            ┌─────────────────────────────────────────┐       │
│            │ Service 层：auth / namespace / topic /   │       │
│            │ apikey / webhook / notify / admin        │       │
│            └────────────────┬────────────────────────┘       │
│                             │                                 │
│            ┌────────────────▼──────────────────┐              │
│            │ Store / DB 层：JDBC + MyBatis-Flex │              │
│            │ JSON 文本字段 + Flyway 迁移        │              │
│            └────────────────┬──────────────────┘              │
└─────────────────────────────┼─────────────────────────────────┘
                              │
          ┌───────────────────▼─────────────────────┐
          │ H2(默认) / MySQL / PostgreSQL /          │
          │ GaussDB(PostgreSQL) / OceanBase(MySQL)   │
          └───────────────────┬─────────────────────┘
                              │
          ┌───────────────────▼─────────────────────┐
          │ 外部通道：SMTP / 钉钉 / 飞书 / 企业微信 / │
          │ 通用出站 Webhook                         │
          └─────────────────────────────────────────┘
```

## 2. 关键请求时序

### 2.1 管理后台修改 Topic

```text
Browser ──PATCH /api/topics/{id}──▶ TopicController
                                      └─▶ TopicService.update()
                                           ├─▶ 校验权限与命名空间归属
                                           ├─▶ 校验 Topic 名称、状态机、JSON Schema
                                           ├─▶ 保存 Topic（含 templates JSON）
                                           └─▶ 返回更新后的 Topic
```

### 2.2 外部应用调用 Webhook

```text
Caller ──POST /api/webhook/{namespace}/{topic}
        Header: Authorization: Bearer <apiKey>   # keyLocation=HEADER
        或 Query: ?key=<apiKey>                  # keyLocation=QUERY
        Body  : {...}
   │
   ▼
WebhookController
   │ 1. 读取 namespace/topic/key/body/clientIp
   ▼
WebhookService
   │ 2. 查找 Topic，要求 status=PUBLISHED
   │ 3. IP 白名单与限流校验
   │ 4. ApiKeyAuthenticator 校验 keyHash、状态、有效期、scope
   │ 5. JSON Schema 校验 inboundFormat
   │ 6. 根据订阅目标创建 NotifyDelivery 持久化任务
   │ 7. 更新 ApiKey lastUsedAt / usageCount，写审计
   ▼
NotifyDeliveryWorker
   │ 8. 取待投递任务，渲染对应通道模板
   │ 9. 调用 NotifyChannel 实现发送
   │ 10. 成功标记完成；失败按策略重试并记录原因
   ▼
返回 200 OK { accepted, traceId, deliveryCount }
```

> `200 OK` 表示 Lite-Alert 已受理并创建投递任务，不承诺下游通道已经投递成功。

## 3. 线程与并发模型

| 组件 | 模型 |
| --- | --- |
| HTTP 接入 | Spring Boot 内置 Tomcat 线程池 |
| 管理接口 | 同步处理，业务数据写入数据库 |
| Webhook 接入 | 同步完成鉴权、校验、任务创建，随后返回 200 |
| 通知派发 | 后台 worker / executor 异步处理数据库中的投递任务 |
| 清理任务 | 定时清理审计、投递历史等可过期数据 |
| 内存缓存 | Caffeine / 内存索引用于热点元数据，加速 ApiKey、Topic 等查询 |

## 4. 失败与降级

- ApiKey 缺失、过期、撤销、scope 不覆盖 → 4xx，拒绝进入投递链路。
- 报文不是 JSON、超过大小限制或 Schema 校验失败 → 4xx，返回带 traceId 的错误体。
- 通知通道不可达 → 投递任务进入失败/重试状态，前端可通过投递记录查看。
- 数据库不可用 → 管理接口和 Webhook 均返回服务端错误，健康检查不通过。
- 模板或转换失败 → 当前目标投递失败或降级为兜底内容，记录失败原因，避免影响其他目标。

## 5. 可观测性

- `/api/health`：容器级轻量健康检查。
- `/api/admin/health`：管理端深度健康信息。
- `/api/audit`：审计日志查询。
- `/api/deliveries`：投递任务列表与详情。
- `/api/admin/stats/**`：趋势、排行等统计接口。
- `TraceIdFilter`：为请求生成 traceId，错误响应与审计记录中保持一致。
