# Implementation Tasks — add-scheduled-tasks

> 实现按依赖顺序分组。遵循 TDD：每个功能点先补测试再实现（见 CLAUDE.md「测试与验证」）。
> 验证命令：后端 `mvn -pl backend -am test -Dskip.frontend=true`；前端 `cd frontend && npm run type-check && npm run build`。

## 1. 基础设施：权限与数据库迁移

- [x] 1.1 在 `Permissions` 新增 `SCHEDULER_TASK_VIEW`、`SCHEDULER_TASK_VIEW_ALL`、`SCHEDULER_TASK_MANAGE`、`SCHEDULER_TASK_PUBLISH` 并加入 `ALL` 列表
- [x] 1.2 确认内置 `r_super_admin` 自动拥有新权限（若需显式挂载则补充种子/迁移）
- [x] 1.3 编写 Flyway 迁移：建 `la_scheduler_task`（含 `draft_config_json`/`published_config_json`/`status`/`cron`/`published_at`）与 `la_scheduler_task_call` 两表，覆盖 h2/mysql/postgresql/gaussdb/oceanbase
- [x] 1.4 验证：5 套库脚本语法正确，H2 启动迁移通过

## 2. 域模型与持久化（`io.litealert.scheduler.domain`）

- [x] 2.1 定义 `SchedulerTask` 实体（status 枚举 DRAFT/PUBLISHED/DISABLED、双配置 JSON 列）与配置 record：`ApiTaskConfig`、`HttpRequestDef`、`BodyConfig`（type + rawType + 字段表/raw 文本）、`AssertionConfig`、`AssertCondition`、`Logic(AND/OR)`
- [x] 2.2 定义 `SchedulerTaskCall` 记录实体与状态枚举（SUCCESS/FAIL）
- [x] 2.3 实现 `SchedulerTaskMapper`/`SchedulerTaskStore`（草稿/发布双读、按状态查 PUBLISHED、保存/发布/状态变更/删除）与 `SchedulerTaskCallStore`（插入记录、列表/详情查询、统计聚合）
- [x] 2.4 单元测试：Store 的草稿编辑不影响 published、未发布不被查询为 PUBLISHED、状态机流转

## 3. API 任务执行器（请求构造 + 自动 Content-Type）

- [x] 3.1 实现 `ApiTaskHttpExecutor`：支持 GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS，注入共享 `HttpClient`
- [x] 3.2 实现四种请求体构造：none / form-data(multipart) / urlencoded / raw(json|xml|text)
- [x] 3.3 实现自动 Content-Type 注入 + 用户显式头优先规则
- [x] 3.4 单元测试：raw-json/xml/text 自动头、form-data boundary、urlencoded、用户显式 Content-Type 覆盖、none 无体

## 4. 多条件响应断言

- [x] 4.1 在 `WebhookResponseAssertor` 新增重载 `check(List<AssertCondition>, Logic, status, contentType, body)`，复用既有 extract/compare
- [x] 4.2 实现 AND/OR 聚合逻辑；HTTP 非 2xx 直接判失败；空条件列表按状态码判定
- [x] 4.3 单元测试：AND 全过/部分不过、OR 任一过、非 2xx 失败、无断言按状态码

## 5. 调度引擎（Cron + 发布即重调度 + 启动恢复）

- [x] 5.1 实现 `SchedulerEngine`：`ThreadPoolTaskScheduler` + `CronTrigger`，内存 `Map<taskId, ScheduledFuture>`
- [x] 5.2 实现 `reschedule(taskId)`：取消旧 future（`mayInterruptIfRunning=false`）→ 按已发布配置+Cron 重建
- [x] 5.3 `@PostConstruct` 启动时加载所有 PUBLISHED 任务重建调度；Cron 表达式校验（非法拒绝）
- [x] 5.4 单次执行流程：触发 → `ApiTaskHttpExecutor` 调用 → 断言 → 写 `SchedulerTaskCall` 记录 → 审计日志
- [x] 5.5 集成测试：首次发布建调度、重新发布热更新、禁用取消调度、服务重启恢复

## 6. 管理 Service + Controller（`io.litealert.scheduler.web`）

- [x] 6.1 `SchedulerTaskService`：创建/编辑草稿/发布/启停/删除，权限校验，调用引擎 reschedule
- [x] 6.2 `SchedulerTaskController`：CRUD + 发布/启停端点，record 请求体，权限注解
- [x] 6.3 调用记录查询：`SchedulerTaskCallController`（列表过滤 + 详情 + 脱敏）
- [x] 6.4 Controller 契约测试：未发布不被调度、无权限 403、非法 Cron 4xx、发布生效

## 7. 前端：任务管理与调用记录页

- [x] 7.1 新增「定时任务」管理页：列表 + 新建/编辑表单（任务类型、方法、URL、请求头表、请求体类型选择 none/form/urlencoded/raw-json/xml/text、Cron、多条件断言 + AND/OR）
- [x] 7.2 实现「在线编辑保存草稿 + 发布」交互（保存只改草稿；发布按钮单独触发）
- [x] 7.3 新增「调用记录」详情页：列表过滤 + 详情抽屉（状态码/耗时/断言结果/脱敏响应摘要）
- [x] 7.4 仪表盘扩展：定时任务调用统计卡片（总数/成功率）+ 调用趋势图（ECharts，复用现有看板风格）
- [x] 7.5 接口统一走 `frontend/src/http`，保持 `/api` 前缀；`npm run type-check` 与 `npm run build` 通过

## 8. 验证与收尾

- [x] 8.1 端到端冒烟：新建 API 任务 → 发布 → 等待 Cron 触发 → 查看调用记录详情 → 仪表盘统计
- [x] 8.2 安全检查：请求头/响应体敏感信息脱敏、权限点覆盖、ApiKey 类敏感不入日志
- [x] 8.3 文档：更新 `docs/design/`（新增定时任务章节）与 README 功能说明
- [x] 8.4 全量验证：`mvn -pl backend -am test -Dskip.frontend=true`、`mvn -pl backend -am package`、前端构建均通过
