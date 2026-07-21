# scheduled-task-management Specification

## Purpose
TBD - created by archiving change add-scheduled-tasks. Update Purpose after archive.
## Requirements
### Requirement: 任务类型与扩展点

系统 SHALL 支持创建定时任务，并要求每个任务指定一个任务类型。系统 SHALL 提供 `API` 与 `TCP` 两种任务类型；任务类型字段 SHALL 作为扩展点，以便后续新增其它类型而不破坏既有数据。

任务类型 SHALL 在创建时由系统校验并持久化，且在创建后不可变更。编辑/保存草稿 SHALL 不允许修改 `taskType`；需要更换类型时 SHALL 删除原任务并新建。

#### Scenario: 新建 API 任务

- **WHEN** 用户创建一个新任务并选择类型为 `API`
- **THEN** 系统创建任务记录，状态为 `DRAFT`，任务类型为 `API`
- **AND** 该任务在被发布之前不会被调度执行

#### Scenario: 新建 TCP 任务

- **WHEN** 用户创建一个新任务并选择类型为 `TCP`
- **THEN** 系统创建任务记录，状态为 `DRAFT`，任务类型为 `TCP`
- **AND** 该任务在被发布之前不会被调度执行

#### Scenario: 拒绝未知任务类型

- **WHEN** 用户创建任务时指定了一个系统不支持的任务类型
- **THEN** 系统拒绝创建并返回校验错误，不写入任何记录

#### Scenario: 任务类型创建后不可变

- **WHEN** 用户对一个已存在的任务执行保存草稿，且请求体中携带了与当前任务不同的 `taskType`
- **THEN** 系统忽略该 `taskType` 字段，任务类型保持创建时的值不变

### Requirement: 草稿与发布配置双轨

每个任务 SHALL 同时持有「草稿配置」与「已发布配置」两份配置。配置载体 SHALL 为多态：按任务类型承载 `ApiTaskConfig` 或 `TcpTaskConfig`（均继承自 `TaskConfig`，带类型鉴别字段 `type`）。在线编辑只修改草稿配置；调度执行只使用已发布配置。仅当任务至少发布过一次（已发布配置存在）时，调度器才会执行它。

发布时 SHALL 将任务级标量（`name`/`description`/`cron`）与通知绑定（`notifyConfigIds`）快照进已发布配置的 `meta`；引擎 SHALL 只从已发布配置的 `meta` 读取权威 Cron 与已发布通知绑定。

任务绑定的通知配置列表（`notifyConfigIds`）SHALL 同样遵循双轨：编辑只改草稿绑定，调度执行与通知派发只使用已发布绑定，发布时把草稿绑定提升为已发布绑定。

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

#### Scenario: 通知绑定随发布生效

- **WHEN** 用户编辑任务的通知配置绑定（草稿）并发布
- **THEN** 已发布通知绑定更新为新绑定
- **AND** 此前未发布前，调度执行仍使用旧的已发布通知绑定

#### Scenario: 配置按类型多态承载

- **WHEN** 系统读取一个任务的已发布配置
- **THEN** API 任务的配置反序列化为 `ApiTaskConfig`
- **AND** TCP 任务的配置反序列化为 `TcpTaskConfig`
- **AND** 二者均带类型鉴别字段 `type`

### Requirement: 任务状态与启停

任务 SHALL 具备 `DRAFT`、`PUBLISHED`、`DISABLED` 三种状态。`PUBLISHED` 状态的任务才会被调度；`DISABLED` 任务暂停调度但保留配置；删除任务 SHALL 同时移除其调度、草稿与已发布配置及关联调用记录。

任务列表与详情 SHALL 额外暴露「是否有未发布改动」的标记，使能直观识别哪些任务的草稿相对线上版本存在差异（详见 `task-draft-review`）。该标记为只读派生态，不影响状态机本身。

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

#### Scenario: 列表标注未发布改动

- **WHEN** 用户查看任务列表
- **THEN** 每个任务附带「是否有未发布改动」的标记
- **AND** 存在未发布改动的任务在列表中可被直观识别

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

### Requirement: 任务复制

系统 SHALL 支持从既有任务复制配置以创建新任务。复制 SHALL 打开新建表单并预填源任务的全部配置，预填内容 SHALL 按源任务的类型对应承载（API 任务预填方法/URL/请求头/请求体/断言；TCP 任务预填 host/port/超时），以及 Cron/描述/通知绑定。新任务名称 SHALL 默认为 `<源任务名>_copy`。复制产生的新任务 SHALL 以草稿状态创建，与源任务互不影响，且任务类型与源任务一致。

#### Scenario: 复制预填配置并以 _copy 命名

- **WHEN** 用户对一个任务点击复制
- **THEN** 打开新建表单，按源任务类型预填其全部配置
- **AND** 新任务类型与源任务一致
- **AND** 名称默认为 `<源任务名>_copy`
- **AND** 保存后创建一个独立的草稿状态新任务

### Requirement: 任务日志跨任务查询

任务日志视图 SHALL 支持按可搜索方式选择任务，并提供「全部任务」选项查询当前用户可见的全部任务调用记录。查询全部任务时，结果按可见范围（`SCHEDULER_CALL_VIEW_ALL` = 全部，否则仅自己）过滤；列表 SHALL 显示每条记录归属的任务名称以便区分。

#### Scenario: 全部任务查询

- **WHEN** 用户在任务日志视图选择「全部任务」
- **THEN** 系统返回当前用户可见的全部任务调用记录
- **AND** 列表显示每条记录归属的任务名称

#### Scenario: 按任务过滤查询

- **WHEN** 用户搜索并选择某一具体任务
- **THEN** 系统仅返回该任务的调用记录

### Requirement: 列表本地化与交互优化

任务列表 SHALL 以中文展示任务状态（草稿/已发布/已停用）而非状态码；存在未发布改动时 SHALL 显示「有改动」标记；行操作 SHALL 以图标（带中文 tooltip）形式呈现，包括编辑、复制、对比、发布、停用/启用、删除。

#### Scenario: 状态中文化与有改动标记

- **WHEN** 用户查看任务列表
- **THEN** 状态以中文展示，存在未发布改动的任务显示「有改动」标记

#### Scenario: 行操作图标化

- **WHEN** 用户查看任务列表的操作列
- **THEN** 各操作以图标呈现并带中文悬浮提示

### Requirement: 任务绑定通知配置

任务 SHALL 可绑定零到多个通知配置（`notifyConfigIds`）。任务编辑界面 SHALL 提供多选入口，且 SHALL 仅允许选择当前用户拥有的通知配置。任务执行后，引擎按已发布通知绑定派发通知（详见 `scheduler-notify-delivery`）。

#### Scenario: 多选自己的通知配置

- **WHEN** 用户在任务编辑页选择通知配置
- **THEN** 可多选，且可选范围仅为自己拥有的通知配置

#### Scenario: 不绑定通知时不发送

- **WHEN** 一个任务未绑定任何通知配置
- **THEN** 执行后不派发任何通知

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

### Requirement: 任务差异按类型比对

任务差异比对（草稿 vs 已发布）SHALL 按任务类型分别比对对应配置字段：API 任务比对请求方法/URL/请求头/请求体/断言/超时；TCP 任务比对 host/port/超时。任务级标量（name/description/cron）与通知绑定（notifyConfigIds）的比对 SHALL 对所有类型生效。「是否有未发布改动」标记 SHALL 基于该按类型比对的结果。

#### Scenario: TCP 任务 host 改动被识别

- **WHEN** 一个已发布的 TCP 任务，其草稿的 host 与已发布配置不同
- **THEN** 差异比对结果包含 host 字段的变更
- **AND** 该任务被标记为「有未发布改动」

#### Scenario: TCP 任务字段比对不误报

- **WHEN** 一个 TCP 任务的草稿与已发布配置的 host/port/超时完全一致
- **THEN** 差异比对结果为空
- **AND** 该任务不被标记为「有未发布改动」

