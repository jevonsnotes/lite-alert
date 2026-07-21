## Why

定时任务三项健壮性/可用性缺口需要补齐：①HTTP 超时硬编码（连接 5s/请求 30s），慢接口会卡住调度线程，用户无法按目标接口特性调优，且超时无明确日志；②调用记录只支持 `limit` 截取，无法分页翻看历史，记录多时只能看最前 200 条；③通知配置 URL 常含敏感凭证（access_token 等），当前明文返回，缺权限分级与启停控制——禁用某通知渠道时只能删除，丢失与任务的绑定关系。

## What Changes

### 1. 定时任务可配超时 + 超时日志
- 任务编辑页增加连接超时、读超时、写超时配置（连接默认 5 秒，读/写默认 30 秒，单位秒，0=不限制）。
- `ApiTaskHttpExecutor` 按任务配置的超时执行；超时/无响应自动断开，并记录日志（任务级 audit + 调用记录 error）。
- 超时配置随草稿/发布双轨（发布才生效）。

### 2. 调用记录分页查询
- 调用记录查询支持分页（`page` + `size`），返回 `{ items, total, page, size }`。
- 单任务查询与全部任务查询均支持分页。

### 3. 通知配置脱敏 + 启停 + 关系保留
- 通知配置 URL 默认脱敏返回（隐藏 query 等敏感部分）；旁边「小眼睛」按钮，有查看明文权限者点击后返回明文。
- 新增禁用/恢复通知配置端点；禁用后该配置不派发通知，但**保留与定时任务的绑定关系**（恢复后自动恢复派发）。
- 启停状态在列表/编辑页可见。

## Capabilities

### New Capabilities
- `scheduler-task-timeouts`: 定时任务的 HTTP 连接/读/写超时可配、默认值、双轨生效、超时断开与日志记录。

### Modified Capabilities
- `task-call-insights`: 调用记录查询支持分页（单任务 + 全部任务），返回分页元数据。
- `scheduler-notify-config`: 通知配置 URL 脱敏与按权限查看明文；通知配置启停（禁用不派发但保留任务绑定关系）。
- `scheduled-task-management`: 任务编辑暴露超时配置字段（随双轨）。

## Impact

- 后端：`ApiTaskHttpExecutor` 支持按任务超时执行 + 超时日志；`ApiTaskConfig`/`Meta` 增加超时字段；`SchedulerTaskCallController`/Store 分页；`SchedulerNotifyConfigController` URL 脱敏 + 启停端点 + 明文查看权限；引擎派发跳过禁用配置。
- 前端：任务编辑页超时配置；调用记录页分页；通知配置 URL 脱敏 + 小眼睛 + 启停按钮。
- 复用：超时配置纳入既有双轨 meta 快照；URL 脱敏复用既有 `PayloadMasker` 思路；启停复用既有 `enabled` 字段。
- 权限：通知配置明文查看需新权限 `SCHEDULER_NOTIFY_VIEW_PLAIN`（或复用 owner 即可查看明文，见 design 开放问题）。
- 无新表：超时随 config JSON；通知配置 `enabled` 已有列；分页走查询参数。
