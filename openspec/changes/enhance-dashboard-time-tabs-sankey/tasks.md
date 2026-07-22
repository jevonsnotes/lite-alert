## 1. 后端：Topic 趋势接口迁移到 from/to（BREAKING）

- [x] 1.1 先写测试暴露差异：`StatsController` 的 `/daily`、`/ranking` 入参改为 `from / to`（`yyyy-MM-dd`），断言按给定绝对区间分桶；旧 `value/unit` 入参不再被接受
- [x] 1.2 `StatsController.window()` 改为按 `from / to` 直接分桶（遍历 `from..to` 每一天），保留「超 365 天截断」保护；`resolveSpan()` 仅在 `from/to` 缺失时回退到 `dashboardDefaultTrend` 换算
- [x] 1.3 `/daily`、`/ranking` 响应仍回显 `from / to`，移除（或降级为信息性）`span` 字段；`dimension / topicId / apiKeyId / limit` 不变
- [x] 1.4 补 `/daily`、`/ranking` 的分桶边界测试（from==to 单天、跨月、超 365 天截断），转绿

## 2. 后端：定时任务统计口径下沉到 calls 分治（BREAKING）

- [x] 2.1 先写测试：`/scheduler/stats` 在仅有 `SCHEDULER_CALL_VIEW`（无 `CALL_VIEW_ALL`）时只统计自己的任务；有 `CALL_VIEW_ALL` 时统计全部
- [x] 2.2 `SchedulerTaskCallController.stats()` 权限要求由 `STATS_VIEW` 改为 `SCHEDULER_CALL_VIEW`；改用 `SchedulerTaskService.visibleTaskIdsForCalls()` 取可见任务集合
- [x] 2.3 `SchedulerTaskCallStore` 新增按 `taskIds` 过滤的 `totals(Set<String>, from, to)` 与 `dailyTrend(Set<String>, from, to)` 重载（参照已有 `countByTasks` / `findPage(taskIds,...)`）；空集合返回零值
- [x] 2.4 `stats()` 端点用新重载计算 total/success/trend；空集合返回 `{total:0,success:0,fail:0,successRate:0,trend:[]}`
- [x] 2.5 既有 `stats` 测试补 calls 分治断言，转绿

## 3. 后端：breakdown 聚合查询与端点

- [x] 3.1 先写测试：`breakdown` 返回 6 维交叉分布 `rows[]`（owner/taskName/taskType/status/assertion/result/count）+ `taskTotals[]`（降序）+ `taskCount`；超出 `limit` 的 `taskName` 改写为「其他」
- [x] 3.2 `SchedulerTaskCallStore` 新增 `breakdown(Set<String> taskIds, Instant from, Instant to, int limit)`：SQL join `la_scheduler_task`（name/owner_id/task_type）+ `la_user`（username），按 6 列 group by 求和；状态码/连接、断言、结果的标签映射在 Java 端做（与 `dailyTrend` 的 Java 分桶风格一致，跨方言安全）
- [x] 3.3 `taskTotals` 单独查询 `group by task_id` 拿 name + count，降序返回完整排名（不截断）；`taskCount` 为去重任务数
- [x] 3.4 `SchedulerTaskCallController.breakdown()`：权限 `SCHEDULER_CALL_VIEW`，复用 `visibleTaskIdsForCalls()`，调 store 返回结构化 Map
- [x] 3.5 个人视图断言：无 `CALL_VIEW_ALL` 时 `rows` 的 `owner` 恒为当前用户 username、只含自己任务
- [x] 3.6 空数据断言：无调用时 `rows:[]`、`taskTotals:[]`、`taskCount:0`，转绿

## 4. 前端：时间区间栏 + TAB 分层重构

- [x] 4.1 `Dashboard.vue` 顶部新增时间区间栏：快捷按钮（1 天/1 周/1 月/1 年）+ `el-date-picker`，统一写入 `from/to` 状态；`onMounted` 拉 `/dashboard/settings` 换算初始 `from/to`
- [x] 4.2 `Dashboard.vue` 改为 `el-tabs`（topic / scheduler）；Topic pane 迁入既有 4 张 metric 卡 + 整体趋势 + Topic/ApiKey 双图；scheduler pane 迁入 3 张 metric 卡 + 调用趋势 + 桑基图占位
- [x] 4.3 Topic 趋势/排行请求改用全局 `from/to` 调 `/daily`、`/ranking`（去掉 value/unit）；scheduler 趋势与桑基用同一 `from/to`
- [x] 4.4 权限 gate：Topic 趋势块受 `STATS_VIEW`；scheduler 趋势卡 + 桑基受 `SCHEDULER_CALL_VIEW`；无权限分别提示/隐藏
- [x] 4.5 `npm run type-check` + `npm run build` 转绿

## 5. 前端：桑基图组件

- [x] 5.1 新建 `frontend/src/components/SchedulerSankeyChart.vue`：props `from/to`，内部调 `/breakdown`，维护 `rows / dimOrder / enabledDims / limit`
- [x] 5.2 ECharts 局部 `echarts.use([SankeyChart, TooltipComponent, CanvasRenderer])`；按 `enabledDims` + `rows` 计算 nodes/links（相邻开启列 groupby 求和）
- [x] 5.3 维度列开关 UI（`el-check-tag`），默认全开；点选关闭即时重算（零网络请求）
- [x] 5.4 Top X 选择（`el-select`，5/10/20/50/100，默认 10），变更后重新请求 `/breakdown?limit=X`
- [x] 5.5 主题（明/暗）套色复用 `useThemeStore`，与既有图表风格一致；空数据显示占位
- [x] 5.6 `npm run type-check` + `npm run build` 转绿；关键交互补可执行验证说明

## 6. 验证与收尾

- [x] 6.1 后端 `mvn -pl backend -am test -Dskip.frontend=true` 转绿
- [x] 6.2 一体打包 `mvn -pl backend -am package` 通过
- [x] 6.3 输出中文变更摘要（含 BREAKING 接口清单）、测试结果、未完成风险
- [x] 6.4 `openspec validate` 通过
