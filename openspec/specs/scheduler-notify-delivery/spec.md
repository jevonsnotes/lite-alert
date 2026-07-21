# scheduler-notify-delivery Specification

## Purpose
TBD - created by archiving change add-scheduler-notify. Update Purpose after archive.
## Requirements
### Requirement: 执行后派发通知

调度引擎在单次任务执行完成并写入调用记录后，SHALL 检查该任务绑定的通知配置，对 triggerOn 匹配当前执行结果的配置逐个发送通知。发送 SHALL 复用既有 HTTP 执行器，请求体按配置模板渲染变量后发出。

#### Scenario: 失败时触发 FAIL 配置

- **WHEN** 任务执行失败，且绑定了 triggerOn=FAIL 的通知配置
- **THEN** 系统向该配置地址发送渲染后的通知请求

#### Scenario: 成功时触发 SUCCESS 配置

- **WHEN** 任务执行成功，且绑定了 triggerOn=SUCCESS 的通知配置
- **THEN** 系统向该配置地址发送通知请求

#### Scenario: ALWAYS 无论成败都触发

- **WHEN** 任务绑定 triggerOn=ALWAYS 的通知配置
- **THEN** 无论执行成功或失败，系统都发送通知请求

#### Scenario: triggerOn 不匹配则不发送

- **WHEN** 任务执行成功，但通知配置 triggerOn=FAIL
- **THEN** 系统不发送该通知

### Requirement: 多通知配置并行派发

任务可绑定多个通知配置，系统 SHALL 对所有 triggerOn 匹配的配置逐个派发。单个通知的发送异常 SHALL NOT 影响其他通知的发送，也 SHALL NOT 影响任务本身的执行结果与调用记录。

#### Scenario: 单个通知失败不影响其他

- **WHEN** 任务绑定了两个通知配置，其中一个发送抛异常
- **THEN** 另一个仍正常发送
- **AND** 任务调用记录仍为成功（若任务本身成功）

#### Scenario: 通知失败仅记审计

- **WHEN** 通知发送失败
- **THEN** 系统记审计日志（scheduler.notify.failed），不重试、不阻塞任务
- **AND** 不影响该任务后续触发

