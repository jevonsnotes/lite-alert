# 12 · 定时任务（Scheduled Tasks）

> Lite-Alert 从「被动通知」扩展为「主动定时调用」。本模块为新增业务域 `io.litealert.scheduler`，首期实现 **API 任务**：按 Cron 周期主动调用外部 HTTP 接口，用响应体断言判断成败，并记录每次调用、在仪表盘统计。

## 1. 核心模型

### 1.1 草稿 / 发布双轨（draft / published）

| 列 | 说明 |
| --- | --- |
| `draft_config_json` | 在线编辑只写这里；调度器**不读取** |
| `published_config_json` | 调度器**只读取**这里；为空代表从未发布 → 不被调度 |
| `status` | `DRAFT` / `PUBLISHED` / `DISABLED`（沿用 Topic 状态机语义） |
| `published_at` | 最近一次发布时间 |

- 「保存」只更新草稿；「发布」将草稿提升为已发布配置，并通知调度引擎 `reschedule`。
- 未发布过的任务（`published_config_json` 为空）即使到达 Cron 时刻也不执行。

### 1.2 调用记录 `la_scheduler_task_call`

每次触发写一行（成功/失败都写）：`task_id, triggered_at, method, url, http_status, duration_ms, success, assertion_passed, error_message, response_excerpt`。`response_excerpt` 落库前脱敏 + 截断（最多 2000 字符）。

## 2. 关键设计决策

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 调度引擎 | Spring `ThreadPoolTaskScheduler` + `CronTrigger`，内存 `Map<taskId, ScheduledFuture>` | 无新依赖；`cancel(false)` 即热更新；启动 `@PostConstruct` 重建调度 |
| 配置存储 | 单表 + 两个 JSON 列（`draft_config_json` / `published_config_json`） | 遵循「复杂字段以 JSON 文本存储」约定，天然实现双轨 |
| HTTP 执行器 | 新建 `ApiTaskHttpExecutor`（共享 `HttpClient`） | `WebhookHttpClient` 是 `final` 且仅 POST JSON/XML，不复用 |
| 请求体 Content-Type | 按类型自动生成；用户显式头优先 | raw-json→`application/json`、raw-xml→`application/xml`、raw-text→`text/plain`、form-data→multipart、urlencoded→`application/x-www-form-urlencoded` |
| 响应断言 | 复用 `WebhookResponseAssertor` 新增多条件重载 | 复用既有 JSONPath/XPath 提取与 7 种操作符；新增 AND/OR 逻辑聚合 |
| 权限 | `SCHEDULER_TASK_VIEW/VIEW_ALL/MANAGE/PUBLISH` | 沿用 `<DOMAIN>_<ACTION>` 命名，挂到内置角色 |

## 3. 执行流程

```text
CronTrigger 到达
   │
   ▼
SchedulerEngine.run(taskId)
   ├─ 读取 published 配置（不可调度则取消调度并退出）
   ├─ ApiTaskHttpExecutor.execute(config)   ── HTTP ──▶ 目标
   ├─ WebhookResponseAssertor.check(conditions, logic, status, ct, body)
   ├─ 写 la_scheduler_task_call（成功/失败均落库）
   └─ AuditLogger（scheduler.task.success / failed）
```

## 4. 断言语义

- HTTP 非 2xx → 直接判失败（不论条件）。
- 无条件 → 仅按 HTTP 状态码判定。
- 多条件：`AND`（全部满足）/ `OR`（任一满足）。
- 操作符：`EQ / NE / CONTAINS / REGEX / GT / LT / EXISTS`。

## 5. 复用关系

| 新增 | 复用既有 |
| --- | --- |
| `scheduler.domain.SchedulerTask(Sore/Call)` | `IdGenerator`、`DbJson`、`JacksonTypeHandler` |
| `ApiTaskHttpExecutor` | `java.net.http.HttpClient`（同 `WebhookHttpClient` 配置） |
| `SchedulerEngine.run` 断言 | `WebhookResponseAssertor`（新增多条件重载，不改既有签名） |
| 调用记录/仪表盘 | `JdbcTemplate` Store 模式（同 `NotifyDeliveryStore`）、`DashboardController` 风格 |

## 6. API 概览

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/scheduler/tasks` | `SCHEDULER_TASK_VIEW` | 任务列表（自己 / VIEW_ALL=全部） |
| GET | `/api/scheduler/tasks/{id}` | `SCHEDULER_TASK_VIEW` | 任务详情（含草稿/已发布配置） |
| POST | `/api/scheduler/tasks` | `SCHEDULER_TASK_MANAGE` | 新建（草稿状态） |
| PATCH | `/api/scheduler/tasks/{id}` | `SCHEDULER_TASK_MANAGE` | 保存草稿（不生效） |
| POST | `/api/scheduler/tasks/{id}/publish` | `SCHEDULER_TASK_PUBLISH` | 发布（新配置生效） |
| POST | `/api/scheduler/tasks/{id}/enable\|disable` | `SCHEDULER_TASK_MANAGE` | 启停 |
| DELETE | `/api/scheduler/tasks/{id}` | `SCHEDULER_TASK_MANAGE` | 删除（含调用记录） |
| GET | `/api/scheduler/tasks/{taskId}/calls` | `SCHEDULER_TASK_VIEW` | 调用记录列表（支持 from/to/success 过滤） |
| GET | `/api/scheduler/calls/{id}` | `SCHEDULER_TASK_VIEW` | 单条调用详情 |
| GET | `/api/scheduler/stats` | `STATS_VIEW` | 仪表盘：总数/成功/失败/成功率/趋势 |

## 7. 约束与后续

- **单实例**：内存调度，多实例需引入 ShedLock（当前非目标）。
- **任务类型**：首期 `API`，新增 `TCP` 连通性探活任务类型（见第 11 节）。`task_type` 作为扩展点预留，新增类型不破坏既有数据。
- **安全**：响应摘要落库前脱敏（password/token/secret/authorization/apikey 类键值）；请求头/正文不入审计。出站目标（URL / host:port）已实现**可配置 SSRF 防护**（见第 12 节）：默认放行，管理员可在系统设置页开启并配置拦截/允许 CIDR 网段与拦截域名后缀，热生效。
- **不做**：失败重试、告警联动、最小触发间隔、TCP 请求/响应探活（形态 B，仅连通性）。

## 8. 草稿变更感知与配置比对

为解决「编辑保存草稿后，用户看不出改没改、改了什么」的不友好体验，新增**只读派生能力**（不改数据模型、不加权限）：

- **未发布改动检测**：`SchedulerTaskDiffService.hasPendingChanges(task)` 规范化比较草稿与已发布配置（空集合/null 归一，避免"没改却显示有改动"的假阳性）。任务列表/详情的 `toView` 顺带返回 `hasPendingChanges`，前端在状态列展示「有未发布改动」徽标。
- **逐字段差异**：`GET /api/scheduler/tasks/{id}/diff` 返回 `{ hasPendingChanges, diffs[] }`，每项含 `field / oldValue / newValue / changeType(ADDED|REMOVED|CHANGED)`，覆盖请求方法/URL/请求头/请求体/断言。
- **发布前预览**：前端「对比已发布」入口与发布按钮均先拉取差异，独立抽屉按字段展示，降低误发布。

差异计算集中在后端 `SchedulerTaskDiffService` 一处，新增配置字段只需扩展比对器；前端按返回项渲染。

## 9. 通知配置（执行后主动推送）

定时任务执行后可主动推送通知到一个可复用的出站 Webhook，实现"任务异常 → 即时告警"。

- **通知配置实体** `SchedulerNotifyConfig`（owner 私有、可复用）：方法/URL/请求头/raw-json 请求体模板 + `triggerOn`（SUCCESS/FAIL/ALWAYS，默认 FAIL）。
- **变量渲染**：请求体模板经 `TemplateRenderer` 渲染，内置执行变量：通用 `taskName/taskId/status/protocol/durationMs/error/triggeredAt`，API 专属 `httpStatus/assertionPassed` 及响应体 `$.response.xxx`，TCP 专属 `tcpTarget/tcpOk`；并支持响应体 JSONPath `{{$.response.xxx}}`（响应体包成 `{response: body}` 作 payload）。TCP 任务下 `httpStatus`/`assertionPassed` 为空字符串。Mustache 默认 HTML 转义会在通知器内反转义，避免 `=`/`"` 等被破坏。前端「可用变量」按通用/API/TCP/助手函数分 tab 展示。
- **双轨绑定**：任务通过 `notifyConfigIds` 绑定多个通知配置；草稿绑定存 `notify_config_ids_json` 列，发布时快照进 `publishedConfig.meta.notifyConfigIds`，引擎与通知派发只用已发布绑定（与 config 双轨一致）。编辑保存草稿不影响运行中的通知。
- **派发**：`SchedulerEngine.run()` 写完调用记录后，遍历已发布通知绑定，对 `shouldFire`（triggerOn 匹配当前成败）者调 `SchedulerNotifier.send`（复用 `ApiTaskHttpExecutor` 发送）。每个通知独立 try-catch，失败仅审计 `scheduler.notify.failed`，不阻塞任务、不影响其他通知、不重试。
- **权限**：`SCHEDULER_NOTIFY_VIEW`/`SCHEDULER_NOTIFY_MANAGE`，owner 私有；任务编辑页仅可选自己的通知配置。
- **API**：`/api/scheduler/notify-configs`（CRUD）；任务创建/保存草稿接口的 `notifyConfigIds` 字段。

## 10. 健壮性控制

### 10.1 HTTP 超时可配
任务可配连接超时、读超时、写超时（`ApiTaskConfig.Timeouts`，秒）。默认连接 5s、读/写 30s，0=不限制。超时随 config 双轨（发布才生效）。`ApiTaskHttpExecutor` 按已发布超时执行：连接超时按值缓存 `HttpClient`（`connectTimeout` 是客户端级），读/写用请求级 `.timeout()`（取 max）。超时异常分类捕获（`HttpTimeoutException`/`ConnectException`）→ 写调用记录 error + 审计 `scheduler.task.timeout`。

### 10.2 调用记录分页
`/calls` 与 `/tasks/{taskId}/calls` 支持 `page`+`size`（默认 page=1、size=20，上限 100），返回 `{items,total,successCount,page,size}`。Store `findPage` 用 `limit/offset` + 独立 `count`。

### 10.3 通知配置 URL 脱敏 + 明文查看
通知配置 URL 默认脱敏（隐藏 `?` 后 query 段）。`GET /{id}/plain-url` 仅 owner 可调用返回明文。前端列表/编辑页脱敏显示 +「查看明文」按钮（小眼睛）。

### 10.4 通知配置启停（保留绑定）
`POST /{id}/disable`、`/enable`（owner）。复用既有 `enabled` 字段 + `shouldFire` 已判 enabled：禁用即不派发，但 `notifyConfigIds` 绑定关系不动，恢复启用自动恢复派发。

## 11. TCP 任务类型（连通性探活）

在 `API` 任务之外新增 `TCP` 任务类型，用于按 Cron 周期探测 `host:port` 的 TCP 连通性，与 `API` 任务共用调度、双轨发布、调用记录、通知链路，仅替换「执行 + 判定」一段。

- **判定**：`java.net.Socket` 建立连接成功 = 成功；连接失败/被拒绝/超时/主机不可解析 = 失败。不发数据、不读响应、无响应断言（形态 A，纯连通性）。
- **配置载体**：`TaskConfig` 多态基类 + `ApiTaskConfig`/`TcpTaskConfig` 子类，Jackson `@JsonTypeInfo` 按 `type` 字段鉴别反序列化。`Meta`（发布快照）与 `Timeouts` 上提到基类，两种类型共用。因定时任务功能未发版，旧 JSON 无需兼容，直接清理。
- **执行器路由**：`SchedulerEngine.run()` 按 `task.getTaskType()` 分流 `runApi` / `runTcp`；当前仅两种类型，用 switch 直读，待第三种类型出现再抽象 `TaskExecutor` 接口（YAGNI）。
- **TCP 执行器** `ApiTaskTcpExecutor`：`Socket` + try-with-resources，连接超时取 `Timeouts.connect`（默认 5s，0=不限制；读/写对 TCP 无意义忽略）；连接成功后立即关闭，不当真实流量处理。
- **任务类型不可变**：`taskType` 创建时校验并持久化，编辑/保存草稿不可改（前端下拉禁用、后端忽略请求中的 taskType，且配置类型与任务类型不一致时忽略配置）；换类型 = 删旧建新。
- **调用记录**：`la_scheduler_task_call` 扩列 `protocol`(API/TCP)、`tcp_target`、`tcp_ok`（5 方言 V4 迁移）；`method/url/http_status` 对 TCP 写 null，`assertion_passed` 恒 null，`response_excerpt` 写 `connected to host:port in Xms` 的人类可读摘要。统计只用 `success` 列，`tcp_ok`/`http_status` 仅展示用。
- **差异比对**：`SchedulerTaskDiffService` 按 `taskType` 分流——TCP 比 host/port/timeouts，API 走既有 method/url/headers/body/assertion/timeouts；标量与 notifyConfigIds 比对对所有类型生效。
- **通知变量**：新增 `{{protocol}}`；TCP 任务下 `{{httpStatus}}`/`{{assertionPassed}}` 为空字符串。`triggerOn` 判定基于 TCP 连接是否成功。

## 12. 出站目标防护（可配置 SSRF 防护）

为应对 SSRF / 内网端口扫描风险，对定时任务（API + TCP）的出站目标做可配置地址防护：

- **接口与实现**：`TaskTargetGuard.check(host, port)` 在 API（URL 解析 host/port，端口缺失按协议补 80/443）与 TCP 任务发起实际连接前调用。真实实现 `CidrTaskTargetGuard`（`@Primary`，零配置接管默认放行 `AllowAllTaskTargetGuard`）注入 `SystemSettingsService`，按配置校验。
- **配置** `SystemSettings.TaskTargetGuardConfig`：`enabled`（默认 false=放行）、`blockedCidrs`（拦截 CIDR，默认含私有网段 10/172.16/192.168、回环 127、链路本地 169.254 含云元数据、0/8）、`allowedCidrs`（允许 CIDR，优先级高于拦截）、`blockedDomains`（域名后缀规则，DNS 解析前按主机名匹配）。存进 `la_system_settings.settings_json`，**复用 `SystemSettingsService` 的 `AtomicReference` 内存缓存，保存即热生效**。
- **校验流程**：host 先按 `blockedDomains` 后缀匹配（不区分大小写）→ 再 `InetAddress.getAllByName` 解析全部 IP，任一命中 `allowedCidrs` 跳过，命中 `blockedCidrs` 抛 `BusinessException(TARGET_BLOCKED)`。host 解析失败不当拦截（交执行器报连接失败）。CIDR 解析结果按配置字符串指纹缓存，稳态探活不重复解析。
- **拦截语义**：抛 `ErrorCode.TARGET_BLOCKED(403)` → 引擎 `runApi`/`runTcp` 的 catch 判失败、写调用记录（error 含命中网段，不含敏感信息）+ 审计 `scheduler.task.target-blocked`；不重试。
- **配置兜底**：`SystemSettingsService.normalize()` 对 `blockedCidrs`/`allowedCidrs` 逐条 CIDR 合法性校验（非法剔除 + warn）、域名后缀校验、去重 trim，不阻断保存。
- **默认实现退场**：`AllowAllTaskTargetGuard` 为普通 `@Component`（非 `@ConditionalOnMissingBean`，避免自匹配问题）；真实实现以 `@Primary` 在 autowire 中胜出，存在即接管。
- **已知残余风险**：DNS rebinding / TOCTOU（校验时与连接时的解析窗口），探活场景影响有限，接受并记录；未来可在连接层 pin 住解析出的 IP。IPv6 覆盖 `::1`/`fc00::/7` 基础网段，精细 IPv6 策略待增强。
