## Why

定时任务首期只支持 `API` 任务类型（主动调用 HTTP 接口 + 响应断言）。但许多监控场景关心的是「某个 TCP 端口是否存活」（数据库、缓存、自定义服务），而非 HTTP 接口可用性。`scheduled-task-management` 规范早已把 `task_type` 作为扩展点预留；本次兑现该扩展点，新增 `TCP` 任务类型，与既有 `API` 任务共用同一套调度/双轨/调用记录/通知链路，仅替换「执行 + 判定」这一段。

## What Changes

- 新增任务类型 `TCP`：按 Cron 周期对 `host:port` 发起 TCP 连接探活，**连接建立成功 = 成功，连接失败/超时/拒绝 = 失败**。不发数据、不读响应、不做响应断言（形态 A，连通性探活）。
- **BREAKING（数据层）**：配置载体由写死的 `ApiTaskConfig` 改为多态基类 `TaskConfig` + 子类 `ApiTaskConfig` / `TcpTaskConfig`，通过 Jackson `@JsonTypeInfo`/`@JsonSubTypes` 按鉴别字段 `type` 分流。因定时任务功能尚未发版，**直接清理旧数据**，不为无鉴别字段的旧 JSON 做兼容。
- `SchedulerTaskType` 枚举新增 `TCP`；`SchedulerTaskTypeClassifier` 自动支持（无需改逻辑，仅文档）。
- 新建 `ApiTaskTcpExecutor`：基于 `java.net.Socket` 建立 TCP 连接，复用 `Timeouts.connect` 作为连接超时；成功返回连接耗时，失败抛异常。
- `SchedulerEngine.run()` 按 `taskType` 路由到对应执行器（API → HTTP 执行 + 响应断言；TCP → 连通性判定）。
- `SchedulerTaskService` 的 `CreateRequest` / `SaveDraftRequest` 的 `config` 字段类型由 `ApiTaskConfig` 改为 `TaskConfig`；`publish` 快照的 `Meta` 上提到 `TaskConfig`。
- `SchedulerTaskDiffService` 改为按 `taskType` 比对对应子类字段（TCP：host/port/timeouts）。
- **BREAKING（数据层）**：`la_scheduler_task_call` 新增列 `protocol`（API/TCP）、`tcp_ok`（bool）；`method/url/http_status` 对 TCP 任务写 null。新增 Flyway V4 迁移（5 个方言：h2/mysql/postgresql/gaussdb/oceanbase）。
- 前端 `SchedulerTasks.vue` 表单按 `taskType` 切换 API 区块（method/url/headers/body/断言）与 TCP 区块（host/port）；`TaskCalls.vue` 列表按 `protocol` 展示对应列。
- `SchedulerNotifier` 通知变量新增 `{{protocol}}`；TCP 任务下 `{{httpStatus}}` 留空。

## Capabilities

### New Capabilities
- `tcp-task-runner`: TCP 连通性探活任务的执行与判定（连接成功/失败/超时，无响应断言）。

### Modified Capabilities
- `scheduled-task-management`: `task_type` 扩展点从「仅 API」变为「API + TCP」；配置载体由单类型改为多态；调用记录新增 `protocol`/`tcp_ok` 字段以承载 TCP 任务结果。
- `api-task-runner`: 配置类 `ApiTaskConfig` 继承多态基类 `TaskConfig`（增加鉴别字段 `type`），不改变既有 HTTP 执行/断言行为。

## Impact

- **后端域**：`io.litealert.scheduler` 新增 `ApiTaskTcpExecutor`、`TcpTaskConfig`、`TaskConfig` 基类；改造 `SchedulerTask`（字段类型）、`SchedulerTaskStore`（多态反序列化）、`SchedulerEngine`（执行器路由）、`SchedulerTaskService`（请求体类型）、`SchedulerTaskDiffService`（按类型比对）、`SchedulerTaskCall`/`SchedulerTaskCallStore`（新列）。
- **数据库**：5 个方言各新增 V4 迁移——重建 `la_scheduler_task_call` 或加列（`protocol`、`tcp_ok`）；`la_scheduler_task` 表结构不变（`task_type` 列已能存 `TCP`，config 列仍为 JSON 文本）。
- **前端**：`SchedulerTasks.vue`（表单分类型区块）、`TaskCalls.vue`（列表/详情按 protocol 展示）。
- **依赖**：无新增三方依赖，TCP 探活使用 JDK `java.net.Socket`。
- **权限**：复用既有 `SCHEDULER_TASK_*` 权限，不新增。
- **安全**：host/port 为用户输入，需校验格式与端口范围（1-65535），禁止明显非法值；调用记录不落 payload（TCP 探活本就无 payload）。
