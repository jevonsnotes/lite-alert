# scheduler-notify-config Specification

## Purpose
TBD - created by archiving change add-scheduler-notify. Update Purpose after archive.
## Requirements
### Requirement: 通知配置生命周期

系统 SHALL 支持创建、编辑、删除定时任务通知配置。每个通知配置 SHALL 包含名称、HTTP 方法、请求地址、自定义请求头、raw-json 请求体模板，以及触发时机 `triggerOn`（SUCCESS / FAIL / ALWAYS，默认 FAIL）。通知配置 SHALL 按 owner 私有存储。

通知配置 SHALL 具备启停状态（`enabled`）：禁用的配置不派发通知，但 SHALL 保留其与定时任务的绑定关系（恢复启用后自动恢复派发）。列表/详情 SHALL 暴露启停状态，并提供禁用/恢复操作。

#### Scenario: 创建通知配置

- **WHEN** 用户创建一个通知配置并填写方法、地址、请求体模板，未指定 triggerOn
- **THEN** 系统创建该配置，triggerOn 默认为 FAIL
- **AND** 该配置的 owner 为当前用户

#### Scenario: 仅 owner 可管理

- **WHEN** 一个用户尝试编辑或删除不属于自己的通知配置
- **THEN** 系统返回 403

#### Scenario: 禁用通知配置不派发但保留绑定

- **WHEN** 用户禁用一个已被任务绑定的通知配置
- **THEN** 后续任务执行不再向该配置派发通知
- **AND** 该配置与任务的绑定关系保留（不删除）
- **AND** 恢复启用后自动恢复派发

### Requirement: 通知配置请求体变量渲染

通知配置的 raw-json 请求体 SHALL 支持模板变量，渲染时注入定时任务执行上下文。内置变量 SHALL 至少包含：通用 `taskName`、`taskId`、`status`、`protocol`、`durationMs`、`error`、`triggeredAt`；API 专属 `httpStatus`、`assertionPassed` 及响应体 JSONPath `$.response.xxx`；TCP 专属 `tcpTarget`、`tcpOk`。TCP 任务下 `httpStatus` 与 `assertionPassed` SHALL 为空字符串；API 任务下 `tcpTarget` 与 `tcpOk` SHALL 为空。

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

### Requirement: 通知配置 URL 脱敏与明文查看

通知配置的请求地址 SHALL 默认脱敏返回（隐藏 query 段等可能含凭证的部分）。系统 SHALL 提供按权限查看明文的能力：有明文查看权限（owner，或具备明文查看权限点）的用户可通过显式请求获得明文 URL。前端 SHALL 在脱敏地址旁提供「查看明文」入口（小眼睛按钮），仅有权者可点出明文。

#### Scenario: 默认脱敏返回

- **WHEN** 用户查询通知配置列表/详情
- **THEN** 返回的 URL 为脱敏形式（query 段被遮蔽）

#### Scenario: 有权限查看明文

- **WHEN** 有明文查看权限的用户请求某通知配置的明文 URL
- **THEN** 系统返回完整明文 URL

#### Scenario: 无权限查看明文

- **WHEN** 无明文查看权限的用户请求明文 URL
- **THEN** 系统返回 403 或仅返回脱敏形式

### Requirement: 脱敏值不覆盖明文与明文切换

编辑通知配置时，若提交的 URL 为脱敏形式（query 段被遮蔽），系统 SHALL 跳过 URL 更新，保留库中真实明文 URL，避免脱敏显示值覆盖真实值导致发送失败。前端明文查看入口 SHALL 支持切换（明文↔脱敏），第二次点击恢复脱敏显示。

#### Scenario: 提交脱敏 URL 不覆盖明文

- **WHEN** 用户编辑通知配置（仅改名称等其它字段），表单中 URL 仍为脱敏形式，提交保存
- **THEN** 系统保留库中真实明文 URL，不被脱敏值覆盖
- **AND** 其它字段正常更新

#### Scenario: 明文切换

- **WHEN** 用户点击明文查看入口展开明文后再次点击
- **THEN** URL 恢复为脱敏显示

