## Why

仪表盘当前把 Topic 统计、定时任务统计、时间窗口选择全部上下平铺在一页，信息密度高且无分层；时间窗口只在 Topic 区有一条不起眼的「最近 N 天」工具条，定时任务区单独用 ISO `from/to`，两套口径割裂。同时定时任务仪表盘只有总量+趋势线，缺少「按任务名 / 类型 / 状态码 / 断言 / 结果」等多维分布洞察，定位异常任务时无图可看。本次将时间区间选择上浮为全局控件、按 TAB 区分 Topic 与定时任务两个业务域，并为定时任务增加可交互的多维桑基图。

## What Changes

- **时间区间上浮**：新增全局时间区间选择栏，置于页面最顶部，Topic TAB 与定时任务 TAB 共用同一 `from/to` 区间。
- **快捷范围 + 绝对区间**：区间选择栏同时支持「最近 1 天 / 1 周 / 1 月 / 1 年」快捷按钮与 `el-date-picker` 绝对起止区间；页面初始默认区间由系统设置 `dashboardDefaultTrend` 换算成 `from/to` 填充。
- **BREAKING（接口契约）**：Topic 统计接口 `/admin/stats/daily`、`/admin/stats/ranking` 由「`value + unit` 相对窗口」改为「`from + to` 绝对区间」入参，分桶逻辑改为按 `from/to` 直接分桶。
- **TAB 分层**：仪表盘用 `el-tabs` 区分「Topic」「定时任务」两个 TAB。Topic TAB 收纳既有命名空间/Topic/ApiKey metric 卡 + 整体趋势 + Topic/ApiKey 双图；定时任务 TAB 收纳既有调用 metric 卡 + 调用趋势 + 新增桑基图。
- **新增桑基图**：定时任务 TAB 增加按 6 个维度串联的桑基图——`owner → 任务名称 → 任务类型 → 状态码/连接 → 断言 → 结果`。默认全维度开启；支持点选关闭任一维度列（前端重算相邻边合并，瞬时响应）；支持 Top X 任务选择（5 / 10 / 20 / 50 / 100），排名之外的任务合并为单个「其他」节点，流量守恒。
- **新增 breakdown 接口**：`GET /api/scheduler/stats/breakdown` 返回 6 维交叉分布全量行 + 任务总量排名，前端据此计算桑基 nodes/links。
- **BREAKING（权限口径）**：定时任务仪表盘的调用趋势统计 `/scheduler/stats` 与新增 `breakdown` 统一下沉到调用记录口径——有 `SCHEDULER_CALL_VIEW_ALL`（或 `STATS_VIEW` 全局）权限看全局所有任务，否则只看自己拥有的任务（复用 `SchedulerTaskService.visibleTaskIdsForCalls()`）。

## Capabilities

### New Capabilities
- `task-call-sankey`: 定时任务调用的多维分布桑基图——6 维串联、维度列可点选关闭、Top X 任务选择与「其他」合并、按 calls 权限口径分治的数据来源。
- `dashboard-layout`: 仪表盘页面分层——全局时间区间栏置顶（绝对 from/to + 快捷范围）+ Topic / 定时任务 TAB 分区，两个业务域各自挂各自 metric 卡与图表。

### Modified Capabilities
- `task-call-insights`: 仪表盘调用统计的时间窗口由相对 `value/unit` 改为绝对 `from/to`；调用趋势统计 `/scheduler/stats` 的权限口径由全局 `STATS_VIEW` 下沉到 calls 分治（`visibleTaskIdsForCalls`）。

## Impact

- **后端域**：`io.litealert.admin.stats` 改造 `StatsController`（`/daily`、`/ranking` 入参改 `from/to`）；`io.litealert.scheduler.web` 改造 `SchedulerTaskCallController.stats()` 权限口径 + 新增 `breakdown` 端点；`SchedulerTaskCallStore` 新增多维分布聚合查询方法。
- **前端**：`Dashboard.vue` 重构为「时间区间栏 + el-tabs + 两 TAB 内容」；新增桑基图组件（ECharts `SankeyChart`，需注册到 echarts/core）。
- **接口契约**：`/admin/stats/daily`、`/admin/stats/ranking`、`/scheduler/stats` 入参变化（BREAKING）；新增 `/scheduler/stats/breakdown`。
- **依赖**：前端 ECharts 需额外注册 `SankeyChart`（已有 echarts/core，无新依赖）。
- **数据库**：无表结构变更（桑基所需字段 `task_id/protocol/http_status/tcp_ok/assertion_passed/success/triggered_at` 已在 `la_scheduler_task_call`；owner/taskType/name 需 join `la_scheduler_task` 与 `la_user`，不加索引本轮可不做，大表场景再评估复合索引）。
- **权限**：不新增权限码；复用 `SCHEDULER_CALL_VIEW` / `SCHEDULER_CALL_VIEW_ALL` / `STATS_VIEW`。
- **安全**：breakdown 只返回聚合计数与 owner `username` / 任务 `name`，不返回调用明细、不返回敏感字段；owner 维度在个人视图退化为当前用户单值。
