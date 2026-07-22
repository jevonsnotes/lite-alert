# task-call-insights Delta

## MODIFIED Requirements

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
