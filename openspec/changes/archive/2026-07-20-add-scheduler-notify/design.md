# Design — add-scheduler-notify

## Context

定时任务目前只执行 + 记录，无主动告警。本变更新增"主动通知"：任务执行后按用户配置的出站 Webhook 推送通知，请求体可插变量。采用探索阶段确认的**方案 C**——新建独立通知配置实体，复用既有 HTTP 执行器与模板引擎，不强行套 `NotifyTarget`（后者只存 URL+secret，方法/头/体模板在 Topic 的 channelTemplate 上，语义不合）。

## Goals / Non-Goals

**Goals:**
- 通知配置 CRUD（owner 私有、可复用），含方法/URL/请求头/raw-json 请求体模板 + triggerOn。
- 定时任务专属变量渲染（含响应体 JSONPath）。
- 引擎执行后按 triggerOn 派发，多配置并行，失败仅审计不阻塞。

**Non-Goals:**
- 不复用 `NotifyTarget`/`NotifyDelivery` 重试体系（首期失败不重试）。
- 不支持 raw 之外请求体类型（仅 raw-json）。
- 不做通知配置共享/跨 owner 可见（owner 私有）。

## Decisions

### D1：通知配置独立实体（方案 C），不复用 NotifyTarget

**选择**：新建 `SchedulerNotifyConfig{id, ownerId, name, method, url, headers, bodyTemplate, triggerOn}`，存 `la_scheduler_notify_config` 表。任务通过 `notifyConfigIds`（JSON 数组）引用。

**理由**：`NotifyTarget` 仅 URL+secret，方法/头/体在 Topic channelTemplate；定时任务的通知"方法+头+体模板"是配置级属性，独立实体最贴合。复用底层 `ApiTaskHttpExecutor` 发送、`TemplateRenderer` 渲染，避免与现有 notify 域语义耦合。

### D2：notify_config_ids 遵循草稿/发布双轨

**选择**：`SchedulerTask` 的 `notifyConfigIds` 同时存草稿与已发布两份（随 `draftConfig`/`publishedConfig` 的 meta 一起快照，或独立 JSON 列对）。发布时草稿绑定提升为已发布绑定；引擎与通知派发只用已发布绑定。

**理由**：与既有"编辑不影响运行、发布才生效"一致。否则编辑通知绑定会立即改变运行行为，破坏双轨不变量。

**实现**：把 `notifyConfigIds` 纳入 `ApiTaskConfig.Meta` 快照（已有 meta 机制），draft 用行级字段、published 用 meta 快照，diff 也覆盖。

### D3：变量作用域——定时任务专属，响应体作 payload

**选择**：`SchedulerNotifier` 构造 system 变量 `{taskName, taskId, status, httpStatus, durationMs, error, triggeredAt, assertionPassed}`，并把**响应体解析为 JsonNode** 作为 payload 传给 `TemplateRenderer.render(template, responsePayload, system)`。这样 `{{taskName}}` 走 system、`{{$.response.data.diff}}` 需要把响应体包成 `{"response": <body>}` 再传。

**理由**：复用现有 `TemplateRenderer` 的内联 JSONPath + Mustache + 函数能力，零新模板代码。响应体包一层 `response` 前缀避免与 system 变量冲突，且让 `$.response.xxx` 语义自洽。

### D4：派发在 run() 末尾，try-catch 隔离每个通知

**选择**：`SchedulerEngine.run()` 写完调用记录后，遍历已发布 `notifyConfigIds`，对 triggerOn 匹配者调 `SchedulerNotifier.notify()`。每个通知单独 try-catch，异常只记审计（`scheduler.notify.failed`），不传播。

**理由**：满足"失败不阻塞任务、不影响其他通知、不重试"。通知异步与否？首期同步发送（通知数量少、体量小），避免引入异步池复杂度；若后续慢可改异步。

### D5：triggerOn 默认 FAIL，匹配逻辑

**选择**：`triggerOn ∈ {SUCCESS, FAIL, ALWAYS}`；匹配规则：`ALWAYS` 总发；`SUCCESS` 仅任务成功发；`FAIL` 仅失败发。默认 FAIL（运维最常见"失败才告警"）。

### D6：权限与可见性

**选择**：新增 `SCHEDULER_NOTIFY_VIEW` / `SCHEDULER_NOTIFY_MANAGE`，挂 super_admin（+ normal_user 给 VIEW）。任务编辑页可选列表 = `findByOwner(currentUser)`。通知配置查询/管理走 owner 私有，不引入 VIEW_ALL（首期不做共享）。

## Risks / Trade-offs

- **[通知慢拖累触发]** → 同步发送，单通知超时由 `ApiTaskHttpExecutor` 的 30s 限制兜底；若目标慢可后续改异步。首期文档提示"通知地址应快速响应"。
- **[通知风暴]** → triggerOn=ALWAYS + 高频 Cron 可能刷屏；首期不强制限流，后续可加每任务通知限流。
- **[响应体不可解析为 JSON]** → `$.response.xxx` 降级为空串（TemplateRenderer 已处理），通知仍发送（用内置变量）。
- **[MODIFIED delta 准确性]** → 已完整复制「草稿与发布配置双轨」原需求并追加场景。

## Migration Plan

- 新增 Flyway V3（5 套库）：`la_scheduler_notify_config` 表 + `la_scheduler_task` 的 notify 绑定（随 config JSON 存储，可能无需新列——见 D2，纳入 meta）。
- 新增 `Permissions` 2 点 + 内置角色挂载。
- 部署即生效；旧任务无通知绑定 → 不派发，行为不变。

## Open Questions

- 通知发送是否需要单独的"通知发送记录"表供前端查看？（首期仅审计日志，后续可加。）
- triggerOn 是否需要"连续 N 次失败才告警"？（首期 Non-Goal。）

## 范围补强：@json 转义 + 可用变量弹窗

实现过程中发现：当响应体含特殊字符（如 HTML 的双引号）时，`{{$.response}}` 原样输出会破坏外层 raw-json 请求体结构，导致通知发送成功但接收方解析失败（静默丢弃，无明显报错）。补强：

### D7：@json 转义函数

**选择**：在 `TemplateFunctions` 新增 `@json`（别名 `@jsonescape`）函数，对变量值做 JSON 字符串转义（`"`->`\"`、`\`->`\\`、换行/控制字符转义），支持 `{{@json($.response)}}` 与 `{{#json}}...{{/json}}` 两种语法。

**理由**：Mustache 默认 HTML 转义只处理 `&<>"'`，不处理 JSON 语境下的双引号嵌入（HTML 转义后的 `&quot;` 在 JSON 里仍是非法）。`@json` 提供真正的 JSON 字符串安全转义，让任意含特殊字符的变量（整段响应体、错误信息等）能安全嵌入 raw-json 请求体。

### D8：可用变量弹窗

**选择**：通知配置编辑页请求体上方加「查看可用变量」按钮，弹窗以表格展示 17 项变量/函数的**用法 + 说明**，分三类（执行上下文/响应体/函数）。

**理由**：原表单内变量 tag 列表信息过密且只列名字不含用法示例；用户（如本次）不知道有哪些函数可用。弹窗集中展示用法（如 `{{@json($.response)}}`）+ 说明，引导正确使用，特别是「整段响应体用 @json 包裹」这一关键提示。
