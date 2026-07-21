# scheduled-task-management (delta)

任务编辑界面暴露 HTTP 超时配置；超时随草稿/发布双轨。

## ADDED Requirements

### Requirement: 任务超时配置入口

任务编辑界面 SHALL 提供连接超时、读超时、写超时配置项（默认连接 5 秒、读/写 30 秒，0=不限制）。超时配置 SHALL 随草稿/发布双轨：保存草稿只改草稿超时，发布才使运行中调度使用新超时。

#### Scenario: 编辑页提供超时配置

- **WHEN** 用户打开任务编辑页
- **THEN** 界面提供连接/读/写超时输入项，默认连接 5 秒、读/写 30 秒

#### Scenario: 超时随发布生效

- **WHEN** 用户编辑超时并发布
- **THEN** 运行中调度使用新的已发布超时
- **AND** 此前未发布时调度仍用旧已发布超时

### Requirement: 启停与发布状态解耦

任务 SHALL 用 `enabled` 作为唯一启停门控，`status`（DRAFT/PUBLISHED）仅表生命周期。启用/禁用 SHALL 只翻转 `enabled` 而不改 `status`。发布 SHALL 复位 `enabled=true`（停用的任务发布后即可运行，无需额外操作）。列表 SHALL 分「发布状态」与「启停」两列独立展示。

#### Scenario: 停用保持已发布状态

- **WHEN** 用户停用一个已发布任务
- **THEN** `enabled` 置 false，`status` 保持 PUBLISHED
- **AND** 列表「发布状态」显示已发布、「启停」显示停用
- **AND** 该任务不被调度

#### Scenario: 停用后直接发布即可运行

- **WHEN** 用户对一个停用任务（enabled=false）点击发布
- **THEN** `enabled` 复位 true，`status` 为 PUBLISHED
- **AND** 调度器恢复执行该任务，无需再点启用
