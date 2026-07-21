# scheduler-task-timeouts

定时任务的 HTTP 连接超时、读超时、写超时可配置；默认值；双轨生效；超时自动断开并记录日志。

## ADDED Requirements

### Requirement: 任务超时配置

每个 API 任务 SHALL 可配置连接超时（connectTimeout）、读超时（readTimeout）、写超时（writeTimeout），单位秒，默认连接 5 秒、读 30 秒、写 30 秒，0 表示不限制。超时配置 SHALL 随草稿/发布双轨：编辑只改草稿，调度执行只用已发布配置（与既有 config 双轨一致）。

#### Scenario: 默认超时

- **WHEN** 用户创建任务未指定超时
- **THEN** 连接超时默认 5 秒，读/写超时默认 30 秒

#### Scenario: 编辑超时不影响运行中的任务

- **WHEN** 用户编辑已发布任务的超时并保存草稿（未发布）
- **THEN** 草稿超时更新，运行中调度仍使用已发布超时

#### Scenario: 发布使新超时生效

- **WHEN** 用户发布含超时改动的任务
- **THEN** 调度器后续执行使用新的已发布超时

### Requirement: 超时执行与断开

`ApiTaskHttpExecutor` SHALL 按任务的已发布超时配置执行请求；连接/读/写超时各自独立生效。超时或无响应时 SHALL 自动断开连接，不无限阻塞调度线程。

#### Scenario: 连接超时自动断开

- **WHEN** 目标接口在连接超时内未建立连接
- **THEN** 请求被中断，该次执行判定为失败
- **AND** 错误信息标注连接超时

#### Scenario: 读超时自动断开

- **WHEN** 连接已建立但响应在读超时内未返回
- **THEN** 请求被中断，执行判定为失败
- **AND** 错误信息标注读超时

### Requirement: 超时日志记录

超时或执行异常 SHALL 记录日志：写入调用记录的 error 字段（脱敏），并记审计日志（`scheduler.task.timeout`/`scheduler.task.failed`），便于排查卡死请求。

#### Scenario: 超时写入调用记录

- **WHEN** 一次执行因超时失败
- **THEN** 调用记录 status=FAIL，errorMessage 标注超时类型
- **AND** 审计日志记录该超时事件

#### Scenario: 超时不泄露敏感信息

- **WHEN** 超时错误信息中含 URL/请求头等可能敏感内容
- **THEN** 入审计/调用记录前按既有脱敏策略处理
