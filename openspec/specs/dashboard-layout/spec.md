# dashboard-layout Specification

## Purpose
仪表盘页面的分层与时间区间交互规范：全局时间区间选择栏置顶，Topic 与定时任务两个业务域用 TAB 分区，各自挂载各自的概览卡与图表。两个 TAB 共用同一对绝对 `from/to` 时间区间。

## Requirements

### Requirement: 全局时间区间选择栏置顶

仪表盘 SHALL 在页面最顶部放置一个全局时间区间选择栏，该选择栏 SHALL 对「Topic」与「定时任务」两个 TAB 同时生效。时间区间以绝对起止 `from / to`（`yyyy-MM-dd`）为唯一真相，两个 TAB 共用同一对值，切换 TAB 时不重置时间区间。

选择栏 SHALL 同时提供两种输入方式：
1. 快捷范围按钮：「最近 1 天 / 1 周 / 1 月 / 1 年」，点击后以当天为基准回算 `from / to` 写入同一对值；
2. `el-date-picker` 绝对起止区间选择，用户手动选定后写入同一对值。

页面初始进入时，时间区间 SHALL 由系统设置 `dashboardDefaultTrend { value, unit }` 换算为绝对 `from / to` 填充（`unit` 为 DAYS/MONTHS/YEARS，MONTHS 按 30 天、YEARS 按 365 天换算，与既有统计天数口径一致）。

#### Scenario: 快捷范围换算为绝对区间

- **WHEN** 用户点击「最近 1 周」快捷按钮
- **THEN** 时间区间被设为 `from = 今天 - 6 天`、`to = 今天`
- **AND** `el-date-picker` 同步显示该区间

#### Scenario: 手动绝对区间覆盖快捷范围

- **WHEN** 用户在 `el-date-picker` 中手动选定 2026-07-01 至 2026-07-15
- **THEN** 时间区间被设为该绝对起止
- **AND** 快捷按钮不再高亮（进入「自定义」态）

#### Scenario: 初始默认区间来自系统设置

- **WHEN** 用户首次进入仪表盘，系统设置 `dashboardDefaultTrend = { value: 14, unit: DAYS }`
- **THEN** 时间区间初始为 `from = 今天 - 13 天`、`to = 今天`

#### Scenario: 两个 TAB 共用时间区间

- **WHEN** 用户在 Topic TAB 选定某时间区间后切换到定时任务 TAB
- **THEN** 定时任务 TAB 的图表与统计使用与 Topic TAB 相同的 `from / to`

### Requirement: Topic 与定时任务 TAB 分区

仪表盘 SHALL 使用 `el-tabs` 将页面分为「Topic」与「定时任务」两个 TAB。

- 「Topic」TAB SHALL 展示 Topic 域概览卡（命名空间数、Topic 数、已发布 Topic 数、活跃 ApiKey 数）、整体趋势图、Topic 调用图与 ApiKey 调用图。
- 「定时任务」TAB SHALL 展示定时任务概览卡（调用总数、成功数、成功率）、调用趋势图与调用多维分布桑基图。
- 每个 TAB SHALL 各自挂载各自的概览卡，互不混排。
- Topic 趋势图块 SHALL 受 `STATS_VIEW` 权限控制，无权限时显示提示而非报错。
- 定时任务 TAB 的趋势卡与桑基图 SHALL 受 `SCHEDULER_CALL_VIEW` 权限控制；整块 SHALL 在用户无相关权限时隐藏或提示。

#### Scenario: Topic TAB 内容

- **WHEN** 用户切到 Topic TAB
- **THEN** 页面展示 Topic 域概览卡、整体趋势图、Topic/ApiKey 双图，且使用全局 `from/to`

#### Scenario: 定时任务 TAB 内容

- **WHEN** 用户切到定时任务 TAB
- **THEN** 页面展示定时任务概览卡、调用趋势图与桑基图，且使用全局 `from/to`

#### Scenario: 无统计权限时 Topic 趋势块提示

- **WHEN** 用户无 `STATS_VIEW` 权限查看 Topic TAB
- **THEN** 趋势图区域显示需要统计权限的提示，不报错
