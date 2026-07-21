# tcp-task-runner Delta

## MODIFIED Requirements

### Requirement: 出站目标防护扩展点

系统 SHALL 提供出站目标防护扩展点 `TaskTargetGuard`，在 TCP 任务发起实际连接前对出站目标（host:port）进行校验。防护行为 SHALL 由 `task-target-guard` 能力定义的配置驱动：防护关闭时放行；防护开启时，命中拦截网段且未被允许网段覆盖的目标 SHALL 被拒绝（详见 `task-target-guard`）。被拦截的目标 SHALL 不发起 TCP 连接，SHALL 判定为执行失败并写调用记录与审计。

#### Scenario: 防护关闭时不拦截

- **WHEN** 出站目标防护开关为关闭，一个 TCP 任务配置任意出站目标
- **THEN** 系统不拦截该目标，正常发起 TCP 连接

#### Scenario: 防护开启时拦截内网目标

- **WHEN** 出站目标防护开启，一个 TCP 任务的目标 IP 命中拦截网段
- **THEN** 系统不发起 TCP 连接
- **AND** 该次执行判定为失败，`tcpOk` 为 false
- **AND** 错误信息记录命中网段，并审计 `scheduler.task.target-blocked`

#### Scenario: TCP 任务连接前调用防护

- **WHEN** 一个已发布的 TCP 任务到达触发时刻
- **THEN** 系统在发起 TCP 连接前先调用 `TaskTargetGuard.check(host, port)`
- **AND** 仅当校验通过后才发起连接
