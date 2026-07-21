## Why

Lite-Alert 目前是**被动通知**系统——只有收到 Webhook 调用才派发通知。运维中大量监控探活、定时拉取指标、定时触发对账等场景需要**主动定时调用**外部接口的能力。新增定时任务让系统从被动消息中转扩展为可配置的主动调度平台，并最大化复用已有的 HTTP 调用与响应断言能力（`WebhookHttpClient`、`WebhookResponseAssertor`）。

## What Changes

- 新增"定时任务"业务域：支持新建任务，选择任务类型（**首期仅 `API` 任务**，预留扩展点）。
- API 任务可配置：HTTP 方法（GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS）、请求地址、请求头、请求体。
- 请求体支持 `none` / `form-data` / `x-www-form-urlencoded` / `raw`（raw 下支持 `json` / `xml` / `text`），并根据选择**自动生成 Content-Type 头**（用户显式设置时以用户值为准）。
- 响应体断言：用 JSONPath/XPath 提取响应值，按操作符（等于/不等于/包含/正则/大于/小于/存在）判断成败；支持**多个条件**，并提供**逻辑模式**（`AND` 全部满足 / `OR` 任一满足）组合多个条件。
- **草稿/发布模型**：在线编辑保存到草稿，**点"发布"后新配置才生效**；发布前调度器继续用旧配置执行。
- 调度引擎按 **Cron 表达式** 触发，**发布即重新调度**（热更新，无需重启）。
- 调用记录：每次执行记录方法、状态码、耗时、是否断言通过、响应体摘要、错误信息，可查看详情。
- 仪表盘新增定时任务调用统计（成功率、调用趋势等）。

## Capabilities

### New Capabilities
<!-- Each creates specs/<name>/spec.md -->
- `scheduled-task-management`: 任务生命周期与配置管理：新建/编辑/删除/启停、草稿与发布工作流、任务类型扩展点（首期 API）。
- `api-task-runner`: API 任务的执行定义与调度：方法/URL/请求头/请求体（多类型 + 自动 Content-Type）、多条件响应断言（AND/OR 逻辑）、Cron 调度触发（发布即重调度）、执行结果落库。
- `task-call-insights`: 调用记录详情查询与仪表盘调用统计展示。

### Modified Capabilities
<!-- openspec/specs/ is empty; dashboard stats are new data sources, not a change to existing Topic/ApiKey stat contracts. -->
- 无

## Impact

- 新增后端域 `io.litealert.scheduler`（`domain` / `web` 子包）：任务实体、调度引擎、API 执行器、记录 Store、Controller。
- 复用并**扩展** `WebhookHttpClient`（全方法 + 多 body 类型）与 `WebhookResponseAssertor`（单条件 → 多条件列表 + AND/OR 逻辑）。
- 新增 Flyway 迁移：定时任务表（草稿/发布配置双轨）+ 调用记录表；`h2` / `mysql` / `postgresql` 三套脚本。
- 新增前端页面：任务管理（在线编辑 + 发布）、调用记录详情；扩展仪表盘卡片。
- 复用权限模型：新增 `SCHEDULER_*` 权限点挂到内置角色。
- **不引入**外部 MQ / 调度框架；沿用单实例 `ScheduledExecutorService` + 内存调度表 + DB 持久化，与现有 worker 模型一致。
