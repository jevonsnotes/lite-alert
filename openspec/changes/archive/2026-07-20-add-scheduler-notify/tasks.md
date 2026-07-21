# Implementation Tasks — add-scheduler-notify

> TDD：先补测试再实现。后端 `mvn -pl backend -am test -Dskip.frontend=true`；前端 `cd frontend && npm run type-check && npm run build`。

## 1. 基础设施：权限与迁移

- [x] 1.1 `Permissions` 新增 `SCHEDULER_NOTIFY_VIEW`、`SCHEDULER_NOTIFY_MANAGE` 并加入 `ALL`
- [x] 1.2 Flyway V3（h2/mysql/postgresql/gaussdb/oceanbase）：建 `la_scheduler_notify_config` 表（id/owner_id/name/method/url/headers_json/body_template/trigger_on/enabled/created_at/updated_at）；给内置角色挂新权限
- [x] 1.3 `ApiTaskConfig.Meta` 增加 `notifyConfigIds`（List<String>），随 publish 快照；diff 覆盖
- [x] 1.4 验证：5 套库迁移语法 + H2 启动通过；`DatabaseInitializerTest` 更新期望

## 2. 通知配置域（实体 / Store / Service / Controller）

- [x] 2.1 `SchedulerNotifyConfig` 实体（TriggerOn 枚举 SUCCESS/FAIL/ALWAYS）+ `SchedulerNotifyConfigStore`（owner 私有 CRUD）
- [x] 2.2 `SchedulerNotifyConfigService`：创建/编辑/删除/列表（owner 校验）、Cron 不需要；请求体模板非空校验
- [x] 2.3 `SchedulerNotifyConfigController`：`/api/scheduler/notify-configs` CRUD，权限注解（VIEW/MANAGE）
- [x] 2.4 单元测试：Store owner 隔离、Service 权限校验、Controller 契约

## 3. 变量渲染 + 派发器

- [x] 3.1 `SchedulerNotifier`：构造 system 变量 + 把响应体包成 `{response: body}` 作 payload，调 `TemplateRenderer.render` 渲染 bodyTemplate
- [x] 3.2 复用 `ApiTaskHttpExecutor`：把渲染后的 body 作为 raw-json 请求发出（构造临时 ApiTaskConfig 或新增发送方法）
- [x] 3.3 triggerOn 匹配逻辑（ALWAYS 总发 / SUCCESS 成功发 / FAIL 失败发）
- [x] 3.4 单元测试：变量渲染（内置 + `$.response.xxx` + 未定义降级）、triggerOn 匹配

## 4. 引擎挂钩 + 任务绑定

- [x] 4.1 `SchedulerEngine.run()` 末尾：取已发布 `notifyConfigIds`，按 triggerOn 过滤，逐个 `SchedulerNotifier.notify`，每个 try-catch + 审计
- [x] 4.2 `SchedulerTaskService`：saveDraft/publish 处理 notifyConfigIds（双轨）；列表/详情 toView 带草稿+已发布绑定
- [x] 4.3 任务编辑接口允许传 notifyConfigIds，校验所选配置属于当前用户
- [x] 4.4 集成测试：失败触发 FAIL 通知、成功触发 SUCCESS 通知、单通知异常不影响其他、不阻塞任务、未绑定不发送

## 5. 前端：通知配置管理 + 任务编辑选项

- [x] 5.1 新增「通知配置」管理页（列表 + 表单：名称/方法/URL/请求头/raw-json 请求体/triggerOn）+ 路由 + 菜单（定时管理下三级 or 平级）
- [x] 5.2 任务编辑表单增加「通知配置」多选（仅拉自己的 `/notify-configs`），展示 triggerOn
- [x] 5.3 请求体编辑区旁展示可用变量提示（taskName/status/httpStatus/error/$.response.xxx 等）
- [x] 5.4 复制任务时一并带出 notifyConfigIds
- [x] 5.5 `npm run type-check` 与 `npm run build` 通过

## 6. 验证与收尾

- [x] 6.1 端到端：建通知配置 → 任务绑定并发布 → mock 失败 → 收到通知请求（变量已渲染）→ mock 成功 → triggerOn=SUCCESS 收到
- [x] 6.2 安全：通知地址/请求头敏感信息不入日志；权限点覆盖；通知失败仅审计
- [x] 6.3 文档：更新 `docs/design/12-scheduled-tasks.md`（增补通知配置章节）
- [x] 6.4 全量验证：后端全测、`mvn -pl backend -am package`、前端构建通过

## 7. 补强：@json 转义函数 + 可用变量弹窗

- [x] 7.1 `TemplateFunctions` 新增 `@json`（别名 `@jsonescape`）函数：JSON 字符串转义（`"`/`\`/控制字符），支持 `{{@json(...)}}` 与 `{{#json}}...{{/json}}` 两种语法
- [x] 7.2 4 个单测：转义双引号/反斜杠、转义换行/控制字符、嵌入 JSON 后合法、section 语法
- [x] 7.3 通知配置编辑页新增「查看可用变量」按钮 + 弹窗，表格展示 17 项变量/函数的用法+说明（执行上下文/响应体/函数三类）；移除原表单内 tag 列表
- [x] 7.4 请求体下方提示「整段响应体请用 @json 包裹避免破坏 JSON」
- [x] 7.5 端到端：HTML 响应 + `{{@json($.response)}}` → 接收端收到合法 JSON（双引号转义，可解析）
