## Context

Lite-Alert 仪表盘（`frontend/src/views/Dashboard.vue`）当前把「命名空间/Topic/ApiKey 概览卡 + Topic 趋势工具条 + 整体趋势 + Topic/ApiKey 双图 + 定时任务概览卡 + 定时任务趋势」全部上下平铺于一页。时间窗口选择仅出现在 Topic 区一条 `el-card` 工具条里，用「最近 N 天/月/年」相对窗口；定时任务区则另起一套 ISO `from/to`。两者变量名不同、作用域不同、切 TAB 不联动。定时任务仪表盘仅有总量 + 成功率 + 时间趋势线，缺多维分布洞察。

本次重构三件事：①把时间区间选择上浮为全局置顶控件，Topic 与定时任务共用；②用 `el-tabs` 把 Topic 域与定时任务域分成两个 TAB；③为定时任务 TAB 增加可交互的 6 维桑基图，并在数据层把定时任务仪表盘的统计口径统一到 calls 分治。

数据底座已经就绪：`la_scheduler_task_call` 表已存齐桑基所需全部字段（`task_id / protocol / http_status / tcp_ok / assertion_passed / success / triggered_at`），任务名/owner/类型需 join `la_scheduler_task`（拿 `name / owner_id / task_type`）与 `la_user`（拿 `username`）。无表结构变更、无 Flyway 迁移。

约束（来自项目规范与既有设计）：
- 前端 `<script setup lang="ts">` + Element Plus + ECharts；接口统一走 `frontend/src/http` 的 `/api` 前缀。
- 后端按业务域分包、Controller 只做适配、业务在 Service、持久化在 Store、构造器注入。
- 5 方言迁移须同步（本次无迁移）。
- 敏感信息不入接口响应；owner 维度只回 `username`，不回 userId 以外标识。
- TDD：先补暴露差异/缺陷的测试，再实现，最后转绿。

## Goals / Non-goals

**Goals**
- 时间区间选择栏置顶，Topic 与定时任务 TAB 共用同一绝对 `from/to`。
- 支持快捷范围（1 天/1 周/1 月/1 年）与 `el-date-picker` 绝对区间两种输入；初始默认由 `dashboardDefaultTrend` 换算为 `from/to`。
- Topic 与定时任务用 `el-tabs` 分层，各自挂各自 metric 卡与图表。
- 定时任务桑基图：6 维串联，维度列可点选关闭（瞬时、前端重算），Top X 任务选择（5/10/20/50/100）+「其他」合并流量守恒。
- 定时任务仪表盘统计口径统一下沉到 calls 分治（`visibleTaskIdsForCalls`），趋势卡与桑基口径一致。

**Non-goals**
- Topic 侧桑基图或多维分析（仅定时任务）。
- 桑基节点下钻跳转调用记录页（后续可加，本轮不做）。
- 为桑基聚合新增数据库复合索引（大表性能问题出现后再评估 `(triggered_at, task_id)`）。
- 改动 `/admin/stats/topic`、`/namespace` 等其它既有统计接口（仅改 `/daily`、`/ranking` 入参契约）。
- 多维分布的历史回算/物化视图（实时聚合）。
- 修改 `dashboardDefaultTrend` 的配置项本身（复用既有 `SystemSettings.Span`，仅前端换算消费方式变化）。

## Decisions

### D1. 时间区间：绝对 from/to 为唯一真相，快捷按钮只是预设

全局只持有一对 `from/to`（`LocalDate` 或 ISO 字符串）。快捷按钮（1 天/7 天/30 天/365 天）点击时把 `to` 钉到今天、`from` 钉到 `to - (n-1) 天`，写入同一对 `from/to`；`el-date-picker` 改值也写同一对。两者互不冲突，picker 当前值即 `from/to`。

- 1 天 = `from = to = today`（当天）。
- 1 周 = `from = today - 6 天`。
- 1 月 = 30 天，1 年 = 365 天。

**系统默认窗口换算**：页面 `onMounted` 拉 `/dashboard/settings` 拿 `dashboardDefaultTrend { value, unit }`，按 `unit`（DAYS/MONTHS/YEARS）换算成天数再算 `from/to`（MONTHS=30 天、YEARS=365 天，与 `StatsController.dayMs` 口径一致）。换算结果作为 picker 初始值。既有 `StatsController.window()` 的「365 天上限」约束不再需要--绝对区间天然由用户控制，但前端仍对跨度做合理上限提示（超 365 天仅作 warning，不阻断，因 `/daily` 已有 365 天分桶上限保护）。

### D2. Topic 趋势接口迁移到 from/to（BREAKING）

`/admin/stats/daily`、`/admin/stats/ranking` 入参由 `value + unit` 改为 `from + to`（`yyyy-MM-dd`）。`StatsController.window()` 改为按 `from/to` 直接分桶：`buckets` 遍历 `from..to` 每一天（保留既有「超 365 天截断」保护）。`resolveSpan()` 仅在 `from/to` 缺失时回退到 `dashboardDefaultTrend` 换算（兼容兜底，正常路径前端必传）。

响应仍带 `from / to`，去掉 `span` 字段（或保留为信息性回显，不再驱动计算）。`dimension / topicId / apiKeyId` 不变。

### D3. 定时任务统计口径统一下沉到 calls 分治（BREAKING）

`SchedulerTaskCallController.stats()` 现用 `STATS_VIEW` + 全局 `callStore.totals(from,to)` / `dailyTrend(from,to)`。改为：
- 权限要求改为 `SCHEDULER_CALL_VIEW`（与 calls 一致）。
- 用 `SchedulerTaskService.visibleTaskIdsForCalls()` 取可见任务 id 集合；空集合 -> 零值返回。
- `totals` / `dailyTrend` 增加按 `taskIds` 过滤的重载（参照已有 `countByTasks` / `findPage(taskIds,...)` 写法）。

`breakdown` 端点同口径。这样趋势卡与桑基图对同一用户看到的数据范围完全一致。

> 说明：`STATS_VIEW` 仍是 Topic 侧 `/daily` 的权限门；定时任务侧改用 `SCHEDULER_CALL_VIEW` 后，一个只有 `SCHEDULER_CALL_VIEW` 没有 `STATS_VIEW` 的用户可看定时任务 TAB 的趋势卡与桑基（自己的），Topic TAB 的趋势图仍需 `STATS_VIEW`。前端按权限分别渲染/隐藏，不混口径。

### D4. 桑基 6 维与标签映射

固定顺序：`owner -> 任务名称 -> 任务类型 -> 状态码/连接 -> 断言 -> 结果`。

各维节点标签取值规则：

| 维度 | 来源 | 值 -> 标签 |
|---|---|---|
| owner | `la_user.username`（join via task.owner_id） | username；个人视图退化为当前用户单值 |
| 任务名称 | `la_scheduler_task.name` | name；Top X 之外合并为「其他」 |
| 任务类型 | `la_scheduler_task.task_type` | `API` / `TCP`（权威值取 task 表，非 call 表 protocol） |
| 状态码/连接 | API: `http_status`；TCP: `tcp_ok` | API 有值->`"200"` 等；API null->`"无响应"`；TCP true->`"已连接"`；TCP false->`"未连接"` |
| 断言 | `assertion_passed` | true->`"通过"`；false->`"失败"`；null->`"无断言"`（TCP 恒 null） |
| 结果 | `success` | true->`"成功"`；false->`"失败"` |

语义提示（写进 spec 注释）：后三维（状态码/连接、断言、结果）存在强冗余--HTTP 非 2xx 直接失败、无条件按状态码判定、有条件再 AND/OR 断言（见 `docs/design/12-scheduled-tasks.md` §4）。因此桑基末端会高度汇聚到「成功/失败」，图的洞察力集中在前三维（owner/任务名/类型分布）。这是设计预期，非缺陷。

### D5. 维度列点选关闭：前端重算，瞬时

后端 `breakdown` 一次性返回全分布行 + 任务总量排名。前端持有一份 `rows[]` 与 `dimOrder`（6 列固定序）。用户点选关闭某列时，前端按「当前开启列」重算邻接边：对每行取相邻两个开启列的值，groupby 求和得到 `links`，nodes 由出现过的值去重生成。ECharts 桑基 `links` 只连相邻节点，关掉中间列需重算跨列流（正是选前端计算的理由）。切换零网络往返。

关闭首列或末列时桑基起点/终点相应左移/右移，ECharts 自动重排。

### D6. Top X 任务 +「其他」合并

排名口径 = 任务在窗口内的**总调用数**（成败合计）。后端 `taskTotals[]` 已按 count 降序返回。前端按 `limit`（5/10/20/50/100）取前 X 个任务名保留原值，第 X+1 起所有任务名改写为「其他」并合并（同名「其他」节点 many-to-one 汇聚，桑基合法、流量守恒）。owner/类型/状态码/断言/结果列不受 Top X 影响，仍按各自值分布。

`limit` 作为 `breakdown` 的入参（默认 10），后端在 `taskTotals` 截断即可；`rows` 返回全量不截断（前端合并「其他」更灵活）。或后端直接在 rows 里把超出 Top X 的 taskName 改写为「其他」--**采用后者**，逻辑集中后端、前端零合并。`taskTotals` 仍返回完整排名供前端展示「共 N 个任务，展示前 X」。

### D7. breakdown 接口契约

```
GET /api/scheduler/stats/breakdown?from=<ISO>&to=<ISO>&limit=10
权限: SCHEDULER_CALL_VIEW（calls 分治，visibleTaskIdsForCalls）

响应:
{
  "rows": [
    { "owner": "alice", "taskName": "订单健康检查", "taskType": "API",
      "status": "200", "assertion": "通过", "result": "成功", "count": 1234 },
    { "owner": "bob", "taskName": "其他", "taskType": "TCP",
      "status": "未连接", "assertion": "无断言", "result": "失败", "count": 56 },
    ...
  ],
  "taskTotals": [ { "taskName": "订单健康检查", "count": 5000 }, ... ],  // 完整排名，降序
  "taskCount": 42,          // 窗口内有调用的任务总数
  "from": "...", "to": "..." 
}
```

- `rows` 的 `taskName` 已按 `limit` 把尾部合并为「其他」。
- 个人视图（无 CALL_VIEW_ALL）：`owner` 列恒为当前用户 username，`rows` 只含自己的任务。
- 空数据：`rows: []`、`taskTotals: []`、`taskCount: 0`，前端显示「无数据」态。

### D8. 前端组件结构

`Dashboard.vue` 重构为：
- 顶部 `<DashboardTimeRangeBar>`（可内联）：快捷按钮 + `el-date-picker` + 区间回显。
- `<el-tabs>`：`topic` / `scheduler` 两个 pane。
  - topic pane：既有 4 张 metric 卡 + 整体趋势 + Topic/ApiKey 双图（迁入，改用全局 `from/to` 调 `/daily`、`/ranking`）。
  - scheduler pane：既有 3 张 metric 卡 + 调用趋势 + 新增 `<SchedulerSankeyChart>`。
- `<SchedulerSankeyChart>`（新组件，`frontend/src/components/`）：props 接收 `from/to`，内部调 `/breakdown`，维护 `rows / dimOrder / enabledDims / limit`，计算 ECharts sankey option；维度列开关用一组 `el-check-tag` 或 `el-checkbox-group`；Top X 用 `el-radio-group` 或 `el-select`；主题（明/暗）套色复用现有 `useThemeStore`。
- ECharts 注册：在桑基组件内 `echarts.use([SankeyChart, ...])`（局部注册，不污染整体 bundle 语义；与 Dashboard 既有 `echarts.use` 风格一致）。

### D9. 权限与空态

- Topic TAB 内容：`canViewStats`（`STATS_VIEW`）控制趋势图块；无权限显示 `el-alert` 提示（既有逻辑保留）。
- 定时任务 TAB 内容：`canViewScheduler`（`SCHEDULER_TASK_VIEW` 或等价）控制整块；趋势卡 + 桑基用 `SCHEDULER_CALL_VIEW`。前端按权限分别 gate。
- 无数据时桑基图区域显示「当前时间区间内无调用记录」占位，不报错。

## Risks / Trade-offs

- **接口契约 BREAKING**：`/daily`、`/ranking`、`/scheduler/stats` 入参变化。因前端与后端同仓同步发布，且无外部第三方消费这些内部统计接口，风险可控；但仍需在变更摘要与 commit 中明确标注 BREAKING。
- **桑基末端汇聚**：后三维强冗余导致末端两根粗流，可能被误读为「图没信息」。通过 spec 注释 + 前端默认全维度 + 引导用户关闭冗余列缓解；不强制改语义。
- **大表聚合性能**：`breakdown` 的多列 group by 在调用记录百万级时可能慢。本轮不加索引，记录为已知残余风险；若上线后慢，加 `(triggered_at, task_id)` 复合索引。
- **「其他」节点过粗**：Top X 很小（如 5）且长尾任务多时，「其他」会汇成很粗的流，挤压头部任务视觉。可接受--粗流本身反映长尾占比；用户可调大 X。
- **owner 维度隐私**：全局视图下 owner 列暴露各用户 username。username 非敏感标识（非 userId/邮箱），且本就是协作可见信息，接受；个人视图下退化为单值，无泄露。
