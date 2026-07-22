# task-call-insights Specification

## Purpose
TBD - created by archiving change add-scheduled-tasks. Update Purpose after archive.
## Requirements
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

### Requirement: 仪表盘调用统计

仪表盘 SHALL 展示定时任务的调用情况，至少包含：指定时间区间内的调用总数、成功率（成功次数 / 总次数）、以及调用趋势（按时间分桶的成功/失败计数）。

调用统计 SHALL 接收绝对时间区间 `from / to`（`yyyy-MM-dd` 或 ISO）作为入参，不再使用相对的 `value / unit` 窗口。

调用统计 SHALL 按调用记录权限口径分治：拥有 `SCHEDULER_CALL_VIEW_ALL`（或全局统计权限）的用户看到全部任务的统计，否则只看到自己拥有任务的统计（复用 `visibleTaskIdsForCalls`）。趋势卡与桑基图 SHALL 使用同一分治口径，保证同一用户在两个图表中看到的数据范围一致。

#### Scenario: 仪表盘展示调用总数与成功率

- **WHEN** 用户在定时任务 TAB 查看某时间区间
- **THEN** 定时任务卡片展示该区间内的调用总数与成功率

#### Scenario: 仪表盘展示调用趋势

- **WHEN** 用户查看定时任务调用趋势图
- **THEN** 图表按时间分桶展示成功与失败次数（复用 ECharts）

#### Scenario: 统计口径分治

- **WHEN** 仅有 `SCHEDULER_CALL_VIEW` 权限的用户查看调用统计
- **THEN** 统计只包含该用户自己任务的调用记录
- **WHEN** 拥有 `SCHEDULER_CALL_VIEW_ALL` 权限的用户查看调用统计
- **THEN** 统计包含全部任务的调用记录

#### Scenario: 无调用时返回零值

- **WHEN** 时间区间内没有任何定时任务调用记录
- **THEN** 仪表盘返回调用总数 0 与成功率 0（或「无数据」态），不报错

