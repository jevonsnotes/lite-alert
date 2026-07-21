# api-task-runner Delta

## ADDED Requirements

### Requirement: API 任务出站目标防护

系统 SHALL 在 API 任务发起 HTTP 请求前，对其出站目标（从配置 URL 解析的 host/port，端口缺失时按协议默认补）施加与 TCP 任务一致的出站目标防护（详见 `task-target-guard`）。防护关闭时放行；防护开启时，命中拦截网段且未被允许网段覆盖的目标 SHALL 被拒绝，不发起 HTTP 请求，判定为执行失败并写调用记录与审计。

#### Scenario: 防护关闭时 API 任务正常请求

- **WHEN** 出站目标防护开关为关闭，一个 API 任务配置任意 URL
- **THEN** 系统正常发起 HTTP 请求

#### Scenario: 防护开启时拦截 API 任务的内网目标

- **WHEN** 出站目标防护开启，一个 API 任务的 URL 解析出的 host 命中拦截网段
- **THEN** 系统不发起 HTTP 请求
- **AND** 该次执行判定为失败，`protocol` 为 `API`
- **AND** 错误信息记录命中网段，并审计 `scheduler.task.target-blocked`

#### Scenario: API 任务连接前调用防护

- **WHEN** 一个已发布的 API 任务到达触发时刻
- **THEN** 系统在发起 HTTP 请求前先从 URL 解析 host/port 并调用 `TaskTargetGuard.check`
- **AND** 仅当校验通过后才发起请求
