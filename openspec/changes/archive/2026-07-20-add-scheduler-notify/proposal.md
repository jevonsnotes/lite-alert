## Why

定时任务目前只"调用接口 + 记录结果"，用户需要主动刷新调用记录才知道任务失败。运维场景需要任务失败（或成功）时**主动推送通知**到一个可配置的 Webhook（钉钉/飞书/企业微信/自建接口），并能利用本次执行产生的变量（状态码、耗时、错误、响应体字段）自行构造通知内容，实现"任务异常 → 即时告警"闭环。

## What Changes

- 新增**定时任务通知配置**（`SchedulerNotifyConfig`）：方法、请求地址、请求头、raw-json 请求体模板；可复用、owner 私有。
- 内置**定时任务专属变量**：taskName/taskId/status/httpStatus/durationMs/error/triggeredAt/assertionPassed，以及响应体 JSONPath（`$.response.xxx`）。
- 通知配置支持 **triggerOn**：SUCCESS / FAIL / ALWAYS（默认 FAIL），控制何时触发。
- 调度引擎执行后**挂钩通知**：按任务绑定的通知配置逐个发送，复用 `ApiTaskHttpExecutor` 发送 + `TemplateRenderer` 渲染变量。
- 任务**可绑定多个通知配置**（多对多）；编辑页可选择**自己拥有的**通知配置。
- 通知发送失败**仅记审计日志，不阻塞任务、不重试**。

## Capabilities

### New Capabilities
- `scheduler-notify-config`: 定时任务通知配置的生命周期与变量渲染——CRUD（owner 私有）、triggerOn、raw-json 请求体模板 + 定时任务专属变量、权限控制。
- `scheduler-notify-delivery`: 调度引擎执行后的通知派发——按 triggerOn 过滤、渲染变量、复用 HTTP 执行器发送、失败仅审计不阻塞。

### Modified Capabilities
- `scheduled-task-management`: 任务可绑定多个通知配置（`notifyConfigIds`），编辑页提供选择入口（仅可选自己拥有的通知配置）。

## Impact

- 新增后端：`SchedulerNotifyConfig` 实体 + Store、`SchedulerNotifyConfigController`、`SchedulerNotifier`（派发 + 渲染）、定时任务变量作用域。
- 复用：`ApiTaskHttpExecutor`（发送）、`TemplateRenderer`（变量渲染）、`AuditLogger`（失败记录）。
- 新增 Flyway 迁移：`la_scheduler_notify_config` 表（5 套库）+ `la_scheduler_task` 增加 `notify_config_ids`（JSON 数组列）。
- 新增权限：`SCHEDULER_NOTIFY_MANAGE` / `SCHEDULER_NOTIFY_VIEW`，挂内置角色。
- 前端：通知配置管理页 + 任务编辑页通知选项（多选自己的通知配置）+ 变量提示。
