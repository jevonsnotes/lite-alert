# scheduled-task-management Delta

## MODIFIED Requirements

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

### Requirement: 任务复制

系统 SHALL 支持从既有任务复制配置以创建新任务。复制 SHALL 打开新建表单并预填源任务的全部配置，预填内容 SHALL 按源任务的类型对应承载（API 任务预填方法/URL/请求头/请求体/断言；TCP 任务预填 host/port/超时），以及 Cron/描述/通知绑定。新任务名称 SHALL 默认为 `<源任务名>_copy`。复制产生的新任务 SHALL 以草稿状态创建，与源任务互不影响，且任务类型与源任务一致。

#### Scenario: 复制预填配置并以 _copy 命名

- **WHEN** 用户对一个任务点击复制
- **THEN** 打开新建表单，按源任务类型预填其全部配置
- **AND** 新任务类型与源任务一致
- **AND** 名称默认为 `<源任务名>_copy`
- **AND** 保存后创建一个独立的草稿状态新任务

## ADDED Requirements

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
