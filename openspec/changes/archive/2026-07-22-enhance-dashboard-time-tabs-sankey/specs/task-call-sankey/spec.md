# task-call-sankey Specification

## Purpose
定时任务调用记录的多维分布桑基图规范：以 6 个维度串联展示调用流向，支持维度列点选关闭、Top X 任务选择与「其他」合并，数据按调用记录权限口径分治（全局 / 仅自己）。

## ADDED Requirements

### Requirement: 多维分布桑基图

定时任务 TAB SHALL 提供一张桑基图，按固定顺序串联 6 个维度展示时间区间内调用记录的分布流向：`owner -> 任务名称 -> 任务类型 -> 状态码/连接 -> 断言 -> 结果`。桑基图的每个节点对应某维度的一个取值，每条边对应相邻两维度之间的调用计数。

各维度节点标签取值规则：
- owner：取任务归属用户的 `username`；
- 任务名称：取任务 `name`，Top X 之外的任务合并为「其他」；
- 任务类型：取 `API` 或 `TCP`（以任务表的 `task_type` 为权威值）；
- 状态码/连接：API 任务取 HTTP 状态码（有值显示如 `200`，无值显示「无响应」）；TCP 任务取连接结果（`已连接` / `未连接`）；
- 断言：取 `通过` / `失败` / `无断言`（TCP 任务恒为「无断言」）；
- 结果：取 `成功` / `失败`。

桑基图所需数据 SHALL 通过 `GET /api/scheduler/stats/breakdown` 获取，入参为 `from / to / limit`。该接口 SHALL 按 calls 权限口径分治：拥有 `SCHEDULER_CALL_VIEW_ALL`（或全局统计权限）的用户看到全部任务的分布，否则只看到自己拥有任务的分布。

#### Scenario: 展示 6 维分布流向

- **WHEN** 用户在定时任务 TAB 查看桑基图
- **THEN** 图表按 owner -> 任务名称 -> 任务类型 -> 状态码/连接 -> 断言 -> 结果顺序串联，展示各维度取值间的调用计数

#### Scenario: API 与 TCP 状态维标签

- **WHEN** 桑基图含 API 任务（状态码 200）与 TCP 任务（连接失败）的调用
- **THEN** 状态码/连接维度同时出现 `200` 与 `未连接` 节点，两者互不相交

#### Scenario: 全局视图 vs 个人视图

- **WHEN** 拥有 `SCHEDULER_CALL_VIEW_ALL` 权限的用户查看桑基图
- **THEN** 图表展示所有任务的分布，owner 维度出现多个用户名
- **WHEN** 仅有 `SCHEDULER_CALL_VIEW` 权限的普通用户查看桑基图
- **THEN** 图表只展示该用户自己任务的分布，owner 维度退化为当前用户单值

#### Scenario: 无调用数据时占位

- **WHEN** 时间区间内没有任何调用记录
- **THEN** 桑基图区域显示「无数据」占位，不报错，不渲染空图

### Requirement: 维度列点选关闭

桑基图 SHALL 默认开启全部 6 个维度。用户 SHALL 能点选关闭任一维度列；关闭某列后，图表 SHALL 在不重新请求后端的前提下，立即按剩余开启维度重算相邻边的流向（关闭中间列时，跨列的流 SHALL 合并到剩余相邻列之间）。

#### Scenario: 关闭中间维度列

- **WHEN** 用户关闭「任务类型」维度列
- **THEN** 图表立即重排为 owner -> 任务名称 -> 状态码/连接 -> 断言 -> 结果
- **AND** 任务名称到状态码/连接的边按调用计数合并，无网络请求

#### Scenario: 关闭首尾维度列

- **WHEN** 用户关闭首列 owner 或末列结果
- **THEN** 图表起点或终点相应左移/右移并重排

### Requirement: Top X 任务选择与「其他」合并

桑基图 SHALL 提供任务数量排名前 X 的选择，可选值为 5 / 10 / 20 / 50 / 100。排名口径为任务在时间区间内的总调用数（成败合计，降序）。排名之外的任务 SHALL 合并为单个「其他」任务节点，流量守恒（「其他」节点的总量等于所有未入榜任务的调用数之和）。

`breakdown` 接口的 `limit` 入参控制 Top X；接口返回的 `rows` 中超出 `limit` 的任务名 SHALL 已被后端改写为「其他」。

#### Scenario: 选择 Top 10

- **WHEN** 用户选择「显示前 10 个任务」
- **THEN** 桑基图任务名称维度展示调用数前 10 的任务节点，外加一个「其他」节点

#### Scenario: 「其他」节点流量守恒

- **WHEN** 时间区间内有 42 个任务有调用，用户选择 Top 5
- **THEN** 「其他」节点的总量等于第 6~42 名任务调用数之和，桑基总流量守恒

#### Scenario: 任务数不超过 Top X

- **WHEN** 时间区间内有 3 个任务有调用，用户选择 Top 10
- **THEN** 桑基图展示 3 个任务节点，不出现「其他」节点

### Requirement: breakdown 接口契约

`GET /api/scheduler/stats/breakdown` SHALL 接收 `from / to / limit`（`limit` 默认 10），按 calls 权限口径分治返回多维分布。

响应 SHALL 包含：
- `rows`：6 维交叉分布行数组，每行含 `owner / taskName / taskType / status / assertion / result / count`，其中超出 `limit` 的 `taskName` 已改写为「其他」；
- `taskTotals`：完整任务总量排名数组（每项含 `taskName / count`，降序），供前端展示「共 N 个任务，展示前 X」；
- `taskCount`：时间区间内有调用的任务总数；
- `from / to`：回显查询区间。

接口 SHALL 仅返回聚合计数与 `username` / 任务 `name`，不返回调用明细或敏感字段。

#### Scenario: 返回全分布与排名

- **WHEN** 用户请求 `breakdown?from=...&to=...&limit=10`
- **THEN** 响应包含 `rows`（已合并「其他」）、`taskTotals`（完整排名）、`taskCount`

#### Scenario: 空数据

- **WHEN** 时间区间内无调用记录
- **THEN** 响应 `rows` 为空数组、`taskTotals` 为空数组、`taskCount` 为 0
