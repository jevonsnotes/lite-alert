# api-task-runner Delta

## ADDED Requirements

### Requirement: API 任务配置多态承载

`API` 任务的配置类（`ApiTaskConfig`）SHALL 继承自多态基类 `TaskConfig`，并 SHALL 携带类型鉴别字段 `type`（值为 `API`）。系统 SHALL 在持久化与反序列化时按该鉴别字段把 JSON 还原为 `ApiTaskConfig`。该多态化 SHALL NOT 改变 API 任务既有的 HTTP 请求定义、请求体类型与自动 Content-Type、多条件响应断言、Cron 调度与执行结果落库行为（上述能力详见既有 requirements）。

任务级发布快照的 `Meta`（name/description/cron/notifyConfigIds）与 `Timeouts`（connect/read/write）SHALL 由基类 `TaskConfig` 承载，`ApiTaskConfig` 继承之，使发布快照与超时配置对 API 与 TCP 任务统一可用。

#### Scenario: API 配置携带类型鉴别字段

- **WHEN** 系统序列化一个 API 任务的配置
- **THEN** 序列化结果包含鉴别字段 `type`，其值为 `API`
- **AND** 反序列化时按该字段还原为 `ApiTaskConfig`

#### Scenario: 多态化不改变既有 HTTP 行为

- **WHEN** 一个已发布的 API 任务到达 Cron 触发时刻
- **THEN** 系统仍按既有的请求方法/URL/请求头/请求体/自动 Content-Type 构造请求
- **AND** 仍按既有的多条件响应断言与 HTTP 状态码判定成功与否

#### Scenario: 发布快照 meta 由基类承载

- **WHEN** 系统发布一个 API 任务
- **THEN** 任务级标量与通知绑定快照进由 `TaskConfig` 基类承载的 `Meta`
- **AND** 引擎从已发布配置的基类 `Meta` 读取权威 Cron 与已发布通知绑定
