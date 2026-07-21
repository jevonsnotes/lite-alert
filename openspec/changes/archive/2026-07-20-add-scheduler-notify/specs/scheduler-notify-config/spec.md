# scheduler-notify-config

定时任务通知配置的生命周期、变量渲染与权限控制。通知配置是 owner 私有、可复用的出站 Webhook 定义，携带方法/地址/请求头/raw-json 请求体模板与触发时机。

## ADDED Requirements

### Requirement: 通知配置生命周期

系统 SHALL 支持创建、编辑、删除定时任务通知配置。每个通知配置 SHALL 包含名称、HTTP 方法、请求地址、自定义请求头、raw-json 请求体模板，以及触发时机 `triggerOn`（SUCCESS / FAIL / ALWAYS，默认 FAIL）。通知配置 SHALL 按 owner 私有存储。

#### Scenario: 创建通知配置

- **WHEN** 用户创建一个通知配置并填写方法、地址、请求体模板，未指定 triggerOn
- **THEN** 系统创建该配置，triggerOn 默认为 FAIL
- **AND** 该配置的 owner 为当前用户

#### Scenario: 仅 owner 可管理

- **WHEN** 一个用户尝试编辑或删除不属于自己的通知配置
- **THEN** 系统返回 403

### Requirement: 通知配置请求体变量渲染

通知配置的 raw-json 请求体 SHALL 支持模板变量，渲染时注入定时任务执行上下文。内置变量 SHALL 至少包含：taskName、taskId、status、httpStatus、durationMs、error、triggeredAt、assertionPassed，并支持以 `$.response.xxx` 形式引用本次执行响应体的 JSONPath 字段。

#### Scenario: 渲染内置变量

- **WHEN** 请求体模板为 `{"text":"任务 {{taskName}} {{status}}：{{error}}"}` 且任务执行失败、error 非空
- **THEN** 渲染后 `{{taskName}}`、`{{status}}`、`{{error}}` 被替换为实际值

#### Scenario: 渲染响应体 JSONPath

- **WHEN** 响应体为 `{"data":{"diff":3}}` 且模板含 `{{$.response.data.diff}}`
- **THEN** 渲染后该处替换为 `3`

#### Scenario: 未定义变量安全降级

- **WHEN** 模板引用了不存在的变量或 JSONPath
- **THEN** 渲染为空串，不抛异常

### Requirement: 变量转义与可发现性

系统 SHALL 提供 `@json` 模板函数，对变量值做 JSON 字符串转义（`"`/`\`/控制字符），使其能安全嵌入 raw-json 请求体的字符串字面量中，避免响应体含特殊字符时破坏外层 JSON。系统 SHALL 在通知配置编辑界面提供「可用变量」入口，集中展示全部可用变量与函数的用法与说明。

#### Scenario: 含特殊字符的值经 @json 转义后安全嵌入

- **WHEN** 请求体模板为 `{"text":"{{@json($.response)}}"}`，且响应体含双引号/换行等字符
- **THEN** 渲染结果为合法 JSON，特殊字符被转义（如 `"` -> `\"`）
- **AND** 接收方能正常解析该 JSON

#### Scenario: 编辑界面可查看全部可用变量

- **WHEN** 用户在通知配置编辑页点击「查看可用变量」
- **THEN** 弹窗展示全部可用变量与函数的用法及说明
- **AND** 区分执行上下文变量、响应体变量、函数三类

### Requirement: 通知配置权限

系统 SHALL 新增 `SCHEDULER_NOTIFY_VIEW`（查看自己的）与 `SCHEDULER_NOTIFY_MANAGE`（增删改）权限点，并挂载到内置超级管理员。任务绑定通知配置时 SHALL 仅允许选择当前用户拥有的通知配置。

#### Scenario: 无管理权限禁止变更

- **WHEN** 没有 `SCHEDULER_NOTIFY_MANAGE` 权限的用户尝试创建/编辑/删除通知配置
- **THEN** 系统返回 403

#### Scenario: 仅能选择自己的通知配置

- **WHEN** 用户在任务编辑页选择通知配置
- **THEN** 可选列表仅包含该用户拥有的通知配置
