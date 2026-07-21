# Design — scheduler-draft-diff-ux

## Context

定时任务清单页编辑后用户无法感知"改没改、改了什么"。现有 `SchedulerTask` 已有 `draftConfig` / `publishedConfig` 双轨字段（见 12-scheduled-tasks.md D2），是天然的差异来源。本变更是**纯只读派生能力**：不新增表、不改数据模型、不改草稿/发布语义，只在后端计算差异、前端展示。

## Goals / Non-Goals

**Goals:**
- 列表/详情直观标注「有无未发布改动」。
- 提供草稿 vs 已发布的逐字段差异视图（方法/URL/请求头/请求体/断言/Cron）。
- 发布前可预览差异，降低误操作。

**Non-Goals:**
- 不做配置版本历史/回滚（首期不引入版本表）。
- 不改写草稿/发布/状态机语义。
- 不引入新权限点。

## Decisions

### D1：差异计算放在后端，统一返回结构化 diff

**选择**：新增只读端点返回 `{ hasPendingChanges, diffs[] }`，diff 项形如 `{ field, path, oldValue, newValue, changeType(ADDED|REMOVED|CHANGED) }`。列表接口在现有 toView 里追加 `hasPendingChanges` 布尔字段。

**理由**：前端各自比对 ApiTaskConfig（含 headers/fields/conditions 列表）逻辑复杂且易不一致；后端单点计算便于测试、保证多端一致。`hasPendingChanges` 走列表接口随查随带，避免 N+1。

**备选**：前端拉全量后本地比对——重复实现、易与后端口径漂移，弃用。

### D2：判定"等价"用规范化序列化比较，而非引用相等

**选择**：把 `ApiTaskConfig`（含子结构）规范化（空集合/null 归一、键排序）后做 JSON 字符串/结构化比较判定 `hasPendingChanges`；逐字段 diff 另走结构化遍历。

**理由**：`{headers: []}` 与 `{headers: null}` 应视为等价，否则会出现"没改却显示有改动"的假阳性，正是用户抱怨的根源。

### D3：差异视图前端用独立抽屉/对话框，复用既有字段元信息

**选择**：`SchedulerTasks.vue` 新增「对比已发布版本」入口 + 差异视图（按字段分组：基本信息/Cron/请求/请求头/请求体/断言）。列表「状态」列旁加「未发布改动」徽标。

**理由**：编辑弹窗空间有限，独立抽屉更适合展示多字段差异；徽标放状态列附近，符合"一眼定位"诉求。

## Risks / Trade-offs

- **[配置字段后续扩展]** → diff 计算集中在后端一处（`SchedulerTaskDiffService`），新增字段只需扩展比对器，前端按返回项渲染。
- **[大配置比对开销]** → 配置体量小（请求体文本上限已有 2000 截断），比对 O(n) 无性能风险。
- **[MODIFIED delta 准确性]** → 已完整复制「任务状态与启停」原需求并追加场景，归档时不会丢内容。

## Migration Plan

- 纯增量：新增只读接口 + 前端视图；无 DB 变更、无权限变更。
- 部署即生效，回滚仅需移除前端入口/接口。

## Open Questions

- 差异视图是否需要"一键应用部分字段到草稿"？（当前 Non-Goal，整份草稿发布即可。）

## 范围增强（迭代补强）

实现过程中按用户反馈补强的能力，补充决策如下：

### D4：任务级标量（name/description/cron）纳入发布快照

**选择**：`ApiTaskConfig` 新增 `Meta{name,description,cron}`；`publish` 时连同 config 一起快照到 `publishedConfig.meta`；diff 比对「当前行标量 vs 已发布 meta 快照」；引擎以 `publishedConfig.meta.cron` 为权威 Cron（行级 `cron` 降级为查询冗余）。

**理由**：行级 name/cron 是单值，无发布快照会导致 ① diff 无法显示这些字段的旧→新；② 编辑 Cron 仅存草稿后，服务重启会用未发布的草稿 Cron 恢复（违反"发布才生效"）。加 meta 快照一并解决两者。对快照特性上线前已发布的旧任务（meta=null），diff 跳过标量比对（不误报）、引擎回退行级 cron，保持向后兼容。

### D5：停用→启用恢复调度（bug 修复）

`setEnabled` 原先在 `store.save` **之前**调 `engine.reschedule`，而 `reschedule` 内部 `findById` 重读的是未持久化的旧 DISABLED 行 → `isSchedulable()` 为假 → 不重建调度。修复为先 `save` 持久化 PUBLISHED 状态，再 `reschedule/unschedule`。回归测试 `SchedulerTaskEnableTest` 锁定"reschedule 必须在 save 之后"。

### D6：任务复制与任务日志跨任务查询

- **复制**：`openCopy(t)` 复用 `openEdit` 填表 + `editingId=null` 切新建 + 名称默认 `<原名>_copy`，保存走既有 `POST /scheduler/tasks`（草稿状态）。
- **跨任务日志**：`GET /api/scheduler/calls`（可选 taskId，无=全部可见任务），Store 新增 `findByTasks/countByTasks`，可见范围由 `visibleTaskIdsForCalls()`（`CALL_VIEW_ALL`=全部，否则仅自己）决定。前端任务下拉 `filterable` + 「全部任务」选项，全部模式列表多一列任务名。

### D7：列表本地化与图标化、权限自动刷新

- 状态中文（草稿/已发布/已停用）、徽标「有改动」、操作列 6 图标（编辑/复制/对比/发布/停启用/删除，带 tooltip）；菜单「任务调用记录」→「任务日志」。
- `AppLayout` 挂载调 `refreshMe()`：权限点变更后刷新页面即同步最新权限，无需退出重登。
