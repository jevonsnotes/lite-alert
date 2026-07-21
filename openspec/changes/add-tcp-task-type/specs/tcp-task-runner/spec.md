# tcp-task-runner Delta

## ADDED Requirements

### Requirement: TCP 连通性探活

系统 SHALL 提供 `TCP` 任务类型：按已发布配置与 Cron 周期，对配置的 `host:port` 发起 TCP 连接探活。判定规则：连接成功建立即判定为成功；连接失败、被拒绝、超时或主机不可达即判定为失败。系统 SHALL 在连接建立后立即关闭连接，且 SHALL NOT 向目标发送任何数据、SHALL NOT 读取任何响应、SHALL NOT 对响应做断言（本版仅连通性探活）。

连接超时 SHALL 取已发布配置的连接超时（`Timeouts.connect`，默认 5 秒，0=不限制）；`Timeouts` 的读/写超时对 TCP 任务 SHALL 被忽略。

#### Scenario: 连接成功判定成功

- **WHEN** 一个已发布的 TCP 任务（host 可达、port 开放）到达 Cron 触发时刻
- **THEN** 系统对该 host:port 发起 TCP 连接，连接建立
- **AND** 该次执行判定为成功
- **AND** 连接建立后立即关闭，不发送数据

#### Scenario: 端口拒绝连接判定失败

- **WHEN** TCP 任务的 host 可达但目标端口无服务监听（收到 RST/拒绝）
- **THEN** 连接建立失败
- **AND** 该次执行判定为失败
- **AND** 错误信息记录连接被拒绝

#### Scenario: 连接超时判定失败

- **WHEN** TCP 任务在连接超时时间内未能建立连接（网络不可达或被防火墙丢弃）
- **THEN** 该次执行判定为失败
- **AND** 错误信息记录连接超时
- **AND** 审计 `scheduler.task.timeout`

#### Scenario: 主机名无法解析判定失败

- **WHEN** TCP 任务配置的 host 无法解析为 IP 地址
- **THEN** 该次执行判定为失败
- **AND** 错误信息记录主机名解析失败

### Requirement: TCP 任务配置

`TCP` 任务的配置 SHALL 包含 `host`（主机名或 IP）与 `port`（端口号）。系统 SHALL 校验 host 非空、port 为 1-65535 的整数，并拒绝非法配置。TCP 任务配置 SHALL 不包含请求方法、请求头、请求体、断言等 HTTP 语义字段。

#### Scenario: 合法 host/port 创建成功

- **WHEN** 用户创建一个 TCP 任务，配置 host=`example.com`、port=3306
- **THEN** 系统创建任务记录，状态为 `DRAFT`，任务类型为 `TCP`
- **AND** 该任务在被发布之前不会被调度执行

#### Scenario: 非法端口号被拒绝

- **WHEN** 用户创建 TCP 任务时配置 port=0 或 port=70000
- **THEN** 系统拒绝创建并返回校验错误，不写入任何记录

#### Scenario: host 为空被拒绝

- **WHEN** 用户创建 TCP 任务时未填写 host
- **THEN** 系统拒绝创建并返回校验错误

### Requirement: TCP 任务执行结果落库

每次 TCP 任务触发执行 SHALL 生成一条调用记录，记录任务 ID、触发时间、协议类型（`TCP`）、耗时、连接是否成功（`tcp_ok`）、错误信息，以及成功时的人类可读摘要。`httpStatus`、`method`、`url`、`assertionPassed`、`responseExcerpt`（HTTP 响应体摘要）对 TCP 任务 SHALL 为空。

TCP 任务成功时，`response_excerpt` SHALL 写入人类可读摘要（如 `connected to host:port in Xms`），便于调用详情页与 API 任务的响应摘要对齐展示。

#### Scenario: TCP 成功生成成功记录

- **WHEN** 一次 TCP 执行连接建立成功
- **THEN** 生成一条状态为成功的调用记录，`protocol` 为 `TCP`、`tcp_ok` 为 true
- **AND** `httpStatus`/`method`/`url` 为空
- **AND** `responseExcerpt` 为形如 `connected to host:port in Xms` 的摘要

#### Scenario: TCP 失败生成失败记录

- **WHEN** 一次 TCP 执行因连接失败、拒绝或超时而失败
- **THEN** 生成一条状态为失败的调用记录，`protocol` 为 `TCP`、`tcp_ok` 为 false
- **AND** 错误信息记录失败原因

#### Scenario: TCP 断言字段为空

- **WHEN** 系统写入任意 TCP 任务的调用记录
- **THEN** `assertionPassed` 为空（TCP 任务无响应断言）

### Requirement: TCP 任务通知派发

TCP 任务执行后 SHALL 按已发布通知绑定派发通知，复用既有通知派发链路。通知渲染变量 SHALL 暴露 `{{protocol}}`（值为 `TCP`）；TCP 任务下 `{{httpStatus}}` 与 `{{assertionPassed}}` SHALL 为空字符串。`triggerOn`（SUCCESS/FAIL/ALWAYS）判定 SHALL 基于 TCP 连接是否成功。

#### Scenario: TCP 失败触发 FAIL 通知

- **WHEN** 一个绑定了 `triggerOn=FAIL` 通知配置的 TCP 任务执行失败
- **THEN** 系统按已发布通知绑定派发通知
- **AND** 通知模板中 `{{protocol}}` 渲染为 `TCP`

#### Scenario: TCP 成功不触发 FAIL 通知

- **WHEN** 一个绑定了 `triggerOn=FAIL` 通知配置的 TCP 任务执行成功
- **THEN** 系统不派发该通知

### Requirement: 出站目标防护扩展点

系统 SHALL 提供出站目标防护扩展点 `TaskTargetGuard`，在 API 与 TCP 任务发起实际连接前对出站目标（host:port）进行校验。默认实现 SHALL 放行全部目标；防护的真正可配置逻辑（黑/白名单网段、开关）SHALL 由独立能力提供。

#### Scenario: 默认放行不拦截连接

- **WHEN** 一个任务（API 或 TCP）配置了任意出站目标，且系统使用默认 `TaskTargetGuard` 实现
- **THEN** 系统不拦截该目标，正常发起连接

#### Scenario: TCP 任务连接前调用防护

- **WHEN** 一个已发布的 TCP 任务到达触发时刻
- **THEN** 系统在发起 TCP 连接前先调用 `TaskTargetGuard.check(host, port)`
- **AND** 仅当校验通过后才发起连接
