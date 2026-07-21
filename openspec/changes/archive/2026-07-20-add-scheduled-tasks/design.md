# Design — add-scheduled-tasks

## Context

Lite-Alert 目前是被动通知系统（Webhook 入站 → 派发）。本变更新增「主动定时调用」能力，复用既有 HTTP 调用（`notify.channel.WebhookHttpClient`）与响应断言（`notify.channel.WebhookResponseAssertor`），组装为新的 `io.litealert.scheduler` 业务域。

相关既有约束：
- 数据持久化以数据库为准，默认 H2，生产可切 MySQL/PostgreSQL/GaussDB/OceanBase；复杂字段以 JSON 文本存储；Flyway 按库类型分目录（`h2/mysql/postgresql/gaussdb/oceanbase`）。
- 单实例部署为主，不引入外部 MQ；后台任务用 `@Scheduled`/worker 轮询 + 数据库持久化。
- 权限模型：`Permissions` 枚举 `<DOMAIN>_<ACTION>` 命名（如 `TOPIC_PUBLISH`），挂到内置 `r_super_admin`。
- 草稿/发布状态机已有先例：`Topic.Status{DRAFT,PUBLISHED,DISABLED}` + `publishedAt`。

## Goals / Non-Goals

**Goals:**
- 新增可配置的定时任务（首期 API 任务），页面配置、发布即生效（热更新，无需重启）。
- 支持完整 HTTP 请求定义（方法/URL/头/体）与多类型请求体（含自动 Content-Type）。
- 支持多条件响应断言（AND/OR 逻辑）。
- 提供调用记录详情与仪表盘调用统计。
- 最大化复用既有代码，按业务域分包，遵循 record 请求体 + 构造器注入 + MyBatis-Flex + Lombok 风格。

**Non-Goals:**
- 不引入 Quartz / 外部 MQ / 分布式调度（单实例内存调度 + DB 持久化）。
- 不做多租户隔离的调度资源配额。
- 首期不实现任务失败重试/告警联动（仅记录失败），后续作为独立变更。
- 首期不支持固定间隔之外的调度方式（仅 Cron）。
- 不在本变更内改造既有 Topic/Webhook 统计契约。

## Decisions

### D1：调度引擎——Spring `ThreadPoolTaskScheduler` + Cron `Trigger`，内存调度表

**选择**：用 Spring 的 `ThreadPoolTaskScheduler`，对每个已发布任务用 `CronTrigger` 调度，在内存中维护 `Map<taskId, ScheduledFuture<?>>`。

**理由**：项目已是 Spring Boot，无需新依赖；`CronTrigger` 自带 Cron 校验与触发计算；`ScheduledFuture.cancel(false)` 即可热更新（取消旧调度、重建新调度）。比 Quartz 轻，比自研 `@Scheduled` 固定延迟更灵活（Cron 表达式）。

**备选**：
- *Quartz*：功能强但重，需持久化表，与「单 JAR 轻量」目标冲突。
- *`@Scheduled` 固定延迟*：无法表达 Cron，且无法运行时热切换表达式。

**热更新流程**：发布 → `reschedule(taskId)`：先 `cancel` 旧 future（不中断正在执行的那一次，`mayInterruptIfRunning=false`），再用新已发布配置 + Cron 重建。启动时 `@PostConstruct` 扫描所有 `PUBLISHED` 任务重建调度。

### D2：草稿/发布双轨配置——单表 + 两个 JSON 列

**选择**：任务表 `la_scheduler_task` 一行存一份任务，含 `draft_config_json` 与 `published_config_json` 两列（JSON 文本），外加 `status`、`cron`（冗余自已发布配置以便查询）、`published_at`。

**理由**：配置是单一复杂对象，遵循项目「复杂字段以 JSON 文本存储」约定，避免关系表 join；草稿/发布双列天然实现「编辑不影响运行」；`published_config_json` 为空即代表未发布过（不被调度）。

**备选**：
- *独立配置表 + version*：更规范但增加 join 复杂度，对单实例轻量场景过度设计。
- *历史版本表*：首期不需要版本回滚，列为 Non-Goal。

### D3：HTTP 执行器——新建 `ApiTaskHttpExecutor`（共享 HttpClient）

**选择**：新建 `scheduler.ApiTaskHttpExecutor`，支持全部方法 + 四种请求体类型 + 自动 Content-Type；复用一个共享 `java.net.http.HttpClient`（连接超时/跟随重定向沿用 `WebhookHttpClient` 配置）。

**理由**：`WebhookHttpClient` 是 `final` 且只暴露 `postJson/postXml`，不适用全方法/多体场景；强行扩展会污染既有 Webhook 通道。新执行器职责单一，便于测试，且避免改动既有稳定代码。

**自动 Content-Type 规则**：`raw-json→application/json`、`raw-xml→application/xml`、`raw-text→text/plain`、`form-data→multipart/form-data; boundary=...`、`urlencoded→application/x-www-form-urlencoded`、`none→不设置`；用户显式提供 `Content-Type` 头时以用户值为准（请求头 map 在设置 Content-Type 时跳过自动注入）。

### D4：响应断言——`WebhookResponseAssertor` 复用 + 多条件适配层

**选择**：复用 `WebhookResponseAssertor` 的 `extract`/`compare` 逻辑。新建轻量方法 `check(List<Condition> conditions, Logic logic, status, contentType, body)`，内部逐条件调用既有提取/比较，再按 `AND`（全过）/`OR`（任一过）聚合。

**理由**：`WebhookResponseAssertor` 已稳定支持 JSONPath/XPath 提取与 7 种操作符（EQ/NE/CONTAINS/REGEX/GT/LT/EXISTS），直接复用避免重复实现与不一致。HTTP 非 2xx 直接判失败的既有语义保留。

**条件模型**（record）：`AssertCondition(path, operator, expected)`；`AssertionConfig(List<AssertCondition> conditions, Logic logic)`。

### D5：调用记录——独立表 `la_scheduler_task_call`

**选择**：每次执行写一条记录：`task_id, triggered_at, method, url, http_status, duration_ms, success, assertion_passed, error_message, response_excerpt`。

**理由**：参考 `NotifyDelivery` 记录模式；`response_excerpt` 按既有脱敏策略（`PayloadMasker` 同款）截断/脱敏后存储，避免敏感泄露与无限增长。失败执行同样落库（区别于现有部分仅记审计的实践）。

### D6：权限——新增 4 个权限点

**选择**：在 `Permissions` 增加 `SCHEDULER_TASK_VIEW`、`SCHEDULER_TASK_VIEW_ALL`、`SCHEDULER_TASK_MANAGE`（含增删改/启停）、`SCHEDULER_TASK_PUBLISH`，并加入 `ALL` 列表；`r_super_admin` 自动拥有（已含 ALL）。沿用 `TOPIC_PUBLISH` 语义单独拆出发布权限。

### D7：Flyway 迁移——5 套库脚本

**选择**：在 `h2/mysql/postgresql/gaussdb/oceanbase` 各加一个版本迁移脚本（建 `la_scheduler_task` 与 `la_scheduler_task_call`），主键用应用层 `IdGenerator` 生成的字符串 ID（与既有实体一致）。

## Risks / Trade-offs

- **[内存调度不持久化在途触发]** → 单实例重启会丢失「下一次应触发时刻」的内存态，但 Cron 由表达式重算，重启后 `@PostConstruct` 重建即恢复；若担心重启窗口漏触发，后续可加「补偿扫描」作为独立变更。
- **[多实例同时调度重复执行]** → 单实例部署为主（Non-Goal 不做分布式）；如未来多实例，需引入 ShedLock。本变更在 `LiteAlertProperties`/文档标注「单实例约束」。
- **[响应体可能含敏感信息]** → `response_excerpt` 落库前脱敏 + 截断（沿用 `PayloadMasker` 规则）；详情查询同样脱敏。
- **[Cron 频率过高压垮目标]** → 首期不强制最小间隔；后续可在系统设置加最小间隔阈值（Non-Goal）。
- **[扩展 WebhookResponseAssertor 的兼容性]** → 以新增重载方法方式扩展，不改既有 `check(Topic.WebhookResponseCheck,...)` 签名，保证 Webhook 通道不受影响。

## Migration Plan

1. 新增 Flyway 脚本（5 套库）——向前兼容，不影响既有表。
2. 新增 `Permissions` 常量——不删除既有权限，无破坏。
3. 部署后：调度引擎启动时扫描 `PUBLISHED` 任务（首期为空，安全）。
4. 回滚：禁用前端入口 + 删除调度引擎 Bean 即可停止调度；DB 表可保留（无既有功能依赖）。

## Open Questions

- 任务是否需要绑定 Namespace（复用既有归属与可见性模型）？倾向「是，便于权限与隔离」——待实现时确认。
- 失败任务是否需要在首期就接入通知目标告警？当前列为 Non-Goal，后续变更处理。
