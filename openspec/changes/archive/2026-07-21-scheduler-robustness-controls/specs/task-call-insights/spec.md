# task-call-insights (delta)

调用记录查询支持分页。

## MODIFIED Requirements

### Requirement: 调用记录列表与详情查询

系统 SHALL 提供调用记录列表查询（支持按任务、时间段、成功/失败过滤）与单条调用记录详情查询。详情 SHALL 包含触发时间、HTTP 方法、请求地址、状态码、耗时、断言结果、错误信息与脱敏后的响应体摘要。

列表查询 SHALL 支持分页（`page` 从 1 起、`size`），返回分页元数据 `{ items, total, page, size }`，按触发时间倒序。单任务查询与全部任务查询均支持分页。

#### Scenario: 按任务查询调用记录

- **WHEN** 用户在调用记录页选择某任务并查询
- **THEN** 系统返回该任务的调用记录列表，按时间倒序

#### Scenario: 查看单条调用记录详情

- **WHEN** 用户打开某条调用记录详情
- **THEN** 系统返回该次执行的方法、地址、状态码、耗时、断言是否通过、错误信息与脱敏响应体摘要

#### Scenario: 敏感信息脱敏

- **WHEN** 调用记录或响应体摘要中包含疑似密钥/令牌等敏感信息
- **THEN** 系统按现有脱敏策略（如 `WebhookResponseAssertor`/`PayloadMasker` 同款规则）对敏感字段脱敏后返回

#### Scenario: 分页查询调用记录

- **WHEN** 用户以 page=2、size=20 查询调用记录
- **THEN** 系统返回第 2 页（第 21~40 条）记录
- **AND** 响应包含 total（总条数）、page、size

#### Scenario: 全部任务分页查询

- **WHEN** 用户在全部任务视图以分页参数查询
- **THEN** 系统按可见范围返回对应页的记录及分页元数据
