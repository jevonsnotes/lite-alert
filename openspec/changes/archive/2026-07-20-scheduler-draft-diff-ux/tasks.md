# Implementation Tasks — scheduler-draft-diff-ux

> TDD：先补测试再实现。后端 `mvn -pl backend -am test -Dskip.frontend=true`；前端 `cd frontend && npm run type-check && npm run build`。

## 1. 后端：差异计算服务

- [x] 1.1 新建 `SchedulerTaskDiffService`：`hasPendingChanges(task)`（规范化比较，空集合/null 归一）+ `diff(task)` 返回结构化 `List<DiffEntry>`
- [x] 1.2 比对覆盖：基本信息(name/description/cron/enabled)、请求(method/url)、请求头(按 name 新增/移除/变更)、请求体(type/rawType/rawText/fields)、断言(logic/conditions)
- [x] 1.3 单元测试：无改动返回 false+空 diff、有改动逐字段、未发布任务全量待发布、空集合等价归一

## 2. 后端：只读接口 + 列表字段

- [x] 2.1 `SchedulerTaskController.toView` 追加 `hasPendingChanges` 字段（详情/列表均带）
- [x] 2.2 新增 `GET /api/scheduler/tasks/{id}/diff`：返回 `{ hasPendingChanges, diffs }`，复用 `SCHEDULER_TASK_VIEW` + `getOrThrow` 可见范围校验
- [x] 2.3 契约测试：无权限 403、不可见任务 403、正常返回 diff 结构

## 3. 前端：未发布改动标记 + 比对视图

- [x] 3.1 `SchedulerTasks.vue` 列表「状态」列旁加「未发布改动」徽标（基于 `hasPendingChanges`）
- [x] 3.2 新增「对比已发布版本」入口（编辑弹窗 + 列表操作列），调用 `/diff` 拉取差异
- [x] 3.3 差异视图（抽屉/对话框）：按字段分组展示 diff（新增/移除/变更高亮）
- [x] 3.4 发布前预览：发布按钮点击后可先查看差异再确认
- [x] 3.5 `npm run type-check` 与 `npm run build` 通过

## 4. 验证与收尾

- [x] 4.1 端到端：编辑保存草稿 → 列表显示徽标 → 打开差异视图看到逐字段差异 → 发布后徽标消失
- [x] 4.2 文档：更新 `docs/design/12-scheduled-tasks.md`（增补草稿变更感知与比对）

## 5. 范围增强（迭代补强）

- [x] 5.1 停用→启用 bug 修复：`setEnabled` 将 `engine.reschedule/unschedule` 移到 `store.save` 之后（reschedule 重读的是已持久化的 PUBLISHED 行）；回归测试 `SchedulerTaskEnableTest`
- [x] 5.2 任务级标量纳入发布快照：`ApiTaskConfig.Meta{name,description,cron}`；`publish` 时快照；diff 比对覆盖 name/description/cron；引擎以 `publishedConfig.meta.cron` 为权威（旧无快照任务回退行级 cron，跳过标量比对）
- [x] 5.3 任务复制：`openCopy(t)` 预填配置 + 名称默认 `<原名>_copy` + 新建模式；操作列复制图标按钮
- [x] 5.4 任务日志跨任务查询：后端 `GET /api/scheduler/calls`（可选 taskId，无=全部可见任务）；`SchedulerTaskCallStore.findByTasks/countByTasks`；前端可搜索任务下拉 +「全部任务」选项 + 全部模式任务名列
- [x] 5.5 列表中文化与图标化：状态中文（草稿/已发布/已停用）、徽标「有改动」、操作列 6 个图标（编辑/复制/对比/发布/停启用/删除）；菜单「任务调用记录」→「任务日志」
- [x] 5.6 前端 AppLayout 挂载时 `refreshMe()` 自动同步权限，权限变更刷新即生效
