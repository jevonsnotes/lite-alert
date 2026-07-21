# scheduled-task-management

定时任务的生命周期与配置管理：任务的创建、在线编辑、发布、启停与删除，以及草稿/发布双轨配置模型。

## ADDED Requirements

### Requirement: 任务类型与扩展点

系统 SHALL 支持创建定时任务，并要求每个任务指定一个任务类型。首期 SHALL 仅提供 `API` 任务类型；任务类型字段 SHALL 作为扩展点，以便后续新增其它类型而不破坏既有数据。

#### Scenario: 新建 API 任务

- **WHEN** 用户创建一个新任务并选择类型为 `API`
- **THEN** 系统创建任务记录，状态为 `DRAFT`，任务类型为 `API`
- **AND** 该任务在被发布之前不会被调度执行

#### Scenario: 拒绝未知任务类型

- **WHEN** 用户创建任务时指定了一个系统不支持的任务类型
- **THEN** 系统拒绝创建并返回校验错误，不写入任何记录

### Requirement: 草稿与发布配置双轨

每个任务 SHALL 同时持有「草稿配置」与「已发布配置」两份配置。在线编辑只修改草稿配置；调度执行只使用已发布配置。仅当任务至少发布过一次（已发布配置存在）时，调度器才会执行它。

#### Scenario: 编辑保存不影响正在运行的任务

- **WHEN** 用户对一个已发布的任务在线编辑请求地址并保存
- **THEN** 草稿配置被更新为新地址
- **AND** 已发布配置保持不变
- **AND** 调度器在下次触发时仍使用旧地址执行

#### Scenario: 发布使新配置生效

- **WHEN** 用户对一个存在草稿改动的任务点击发布
- **THEN** 系统将草稿配置提升为已发布配置
- **AND** 记录发布时间（`publishedAt`）
- **AND** 调度器使用新配置重新调度，后续触发按新配置执行

#### Scenario: 未发布任务不被调度

- **WHEN** 一个任务从未被发布过（仅存在草稿配置）
- **THEN** 调度器不为其建立调度
- **AND** 即使到达 Cron 触发时刻也不执行该任务

### Requirement: 任务状态与启停

任务 SHALL 具备 `DRAFT`、`PUBLISHED`、`DISABLED` 三种状态。`PUBLISHED` 状态的任务才会被调度；`DISABLED` 任务暂停调度但保留配置；删除任务 SHALL 同时移除其调度、草稿与已发布配置及关联调用记录。

#### Scenario: 禁用任务暂停调度

- **WHEN** 用户将一个 `PUBLISHED` 任务置为 `DISABLED`
- **THEN** 调度器取消其调度
- **AND** 任务配置与历史调用记录保留
- **AND** 后续不再触发该任务

#### Scenario: 重新启用恢复调度

- **WHEN** 用户将一个 `DISABLED` 任务重新置为 `PUBLISHED`
- **THEN** 调度器按其已发布配置与 Cron 表达式重建调度

#### Scenario: 删除任务清理关联数据

- **WHEN** 用户删除一个任务
- **THEN** 系统取消其调度、删除草稿与已发布配置
- **AND** 删除该任务的调用记录（或按保留策略归档）

### Requirement: Cron 表达式校验

每个任务 SHALL 配置一个 Cron 表达式作为调度规则。系统 SHALL 在保存草稿与发布时校验 Cron 表达式合法，并拒绝非法表达式。

#### Scenario: 合法 Cron 保存成功

- **WHEN** 用户为一个任务配置合法的 Cron 表达式（如 `0 */5 * * * *`）并保存
- **THEN** 系统接受该表达式并写入草稿配置

#### Scenario: 非法 Cron 被拒绝

- **WHEN** 用户配置了无法解析的 Cron 表达式
- **THEN** 系统返回校验错误，不保存该配置

### Requirement: 权限控制

定时任务的管理操作 SHALL 受 RBAC 权限模型约束。系统 SHALL 新增 `SCHEDULER_TASK_MANAGE`（管理：增删改/发布/启停）与 `SCHEDULER_TASK_VIEW`（查看：任务列表、配置、调用记录）权限点，并挂载到内置超级管理员角色。

#### Scenario: 无管理权限禁止变更

- **WHEN** 一个没有 `SCHEDULER_TASK_MANAGE` 权限的用户尝试创建、编辑、发布或删除任务
- **THEN** 系统返回 403，拒绝操作

#### Scenario: 仅查看权限可读不可写

- **WHEN** 一个仅有 `SCHEDULER_TASK_VIEW` 权限的用户访问任务列表与调用记录
- **THEN** 系统允许读取
- **AND** 但该用户尝试修改任务时被拒绝
