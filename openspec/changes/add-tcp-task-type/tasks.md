## 1. 配置多态骨架（TaskConfig 基类）

- [x] 1.1 新建 `TaskConfig` 抽象基类：`type` 鉴别字段（`@JsonTypeInfo(use=NAME, include=PROPERTY, property="type")` + `@JsonSubTypes({API,TCP})`）、上提 `Meta` 与 `Timeouts` 字段及 getter/setter；为 `Meta`/`Timeouts` 的引用调整包可见性
- [x] 1.2 `ApiTaskConfig` 改为 `extends TaskConfig`，移除已上提的 `meta`/`timeouts` 字段（保留方法签名委托基类），标注 `@JsonTypeName("API")`；确认既有 HTTP 字段（method/url/headers/body/assertion）不动
- [x] 1.3 新建 `TcpTaskConfig extends TaskConfig`：`host`(String)、`port`(Integer)，`@JsonTypeName("TCP")`；不预留形态 B 字段
- [x] 1.4 写 Jackson 多态序列化测试：API 配置序列化含 `type=API`、反序列化还原 `ApiTaskConfig`；TCP 配置同理还原 `TcpTaskConfig`；未知 type 反序列化容错（不抛、回退 null）
- [x] 1.5 `SchedulerTaskType` 枚举新增 `TCP`（确认 `SchedulerTaskTypeClassifier` 无需改逻辑，仅更新类注释）

## 2. Store / Service 多态适配

- [x] 2.1 `SchedulerTask` 的 `draftConfig`/`publishedConfig` 字段类型由 `ApiTaskConfig` 改为 `TaskConfig`；`draftConfig` 默认值改为按需构造（API 任务默认 `ApiTaskConfig`）
- [x] 2.2 `SchedulerTaskStore.map()` 把 `json.read(..., ApiTaskConfig.class)` 改为 `json.read(..., TaskConfig.class)`，并包 try-catch 容错（反序列化失败回退 null + 记日志，不拖垮启动恢复）
- [x] 2.3 `SchedulerTaskService.CreateRequest`/`SaveDraftRequest` 的 `config` 字段类型改为 `TaskConfig`；`create()` 按 `taskType` 构造默认草稿配置（API→`ApiTaskConfig`，TCP→`TcpTaskConfig`）
- [x] 2.4 `SchedulerTaskService.saveDraft()` 显式忽略请求中的 `taskType`（taskType 创建后不可变，D7）；TCP 任务保存时校验 host 非空、port 1-65535
- [x] 2.5 `SchedulerTaskService.publish()` 把 `setMeta(...)` 调到 `TaskConfig` 基类方法；确认 `published` 快照写的是基类 `Meta`（D2 不变量）
- [x] 2.6 写 Service 测试：新建 TCP 任务落库 taskType=TCP 且 config 反序列化为 `TcpTaskConfig`；保存草稿带不同 taskType 时被忽略；非法 port 被拒绝

## 3. TCP 执行器与防护扩展点

- [x] 3.1 新建 `TaskTargetGuard` 接口：`void check(String host, int port)`；新建默认实现 `AllowAllTaskTargetGuard`（`@Component`，空实现，放行全部）
- [x] 3.2 新建 `ApiTaskTcpExecutor`：`java.net.Socket` + try-with-resources，`connect(InetSocketAddress, connectTimeoutMs)`，连接成功返回 `Result(true,null)`；不发数据不读响应；连接超时取 `Timeouts.connect`（默认 5s）
- [x] 3.3 写 `ApiTaskTcpExecutor` 测试：连本地未监听端口→失败（拒绝）；连超时端口（不可达 IP）→失败（超时）；用真实监听端口（如 `ServerSocket` 临时端口）→成功（纯函数化或用本地回环）

## 4. Engine 执行器路由

- [x] 4.1 `SchedulerEngine.run()` 拆分：按 `task.getTaskType()` switch 路由 `runApi(task, ApiTaskConfig)` / `runTcp(task, TcpTaskConfig)`；`runApi` 保留既有 HTTP + 断言逻辑原样
- [x] 4.2 `runTcp`：调 `guard.check(host,port)` → `tcpExecutor.execute(config)` → 成功写 `tcpOk=true`、`httpStatus=null`、`excerpt="connected to host:port in Xms"`；失败分类捕获 `SocketTimeoutException`/`ConnectException`/`UnknownHostException` 写 error + 审计 `scheduler.task.timeout`
- [x] 4.3 `SchedulerEngine` 注入 `TaskTargetGuard` 与 `ApiTaskTcpExecutor`；`runApi` 在发 HTTP 前从 URL 解析 host/port 调 `guard.check`
- [x] 4.4 `SchedulerTaskCall` 新增 `protocol`(String)、`tcpOk`(Boolean) 字段；`Builder` 默认值 API 任务写 `protocol=API`、TCP 写 `protocol=TCP`
- [x] 4.5 写 Engine 测试：TCP 成功路径落 `protocol=TCP/tcpOk=true/success=true`；TCP 失败路径落 `tcpOk=false/success=false` + error；`assertionPassed` 恒 null；默认 guard 不拦截

## 5. Call Store 与 DB 迁移

- [x] 5.1 `SchedulerTaskCallStore.insert()` SQL 与参数新增 `protocol`、`tcp_ok` 列；`map()` 读取新列
- [x] 5.2 5 方言各新建 `V4__scheduler_tcp_call_columns.sql`：`alter table la_scheduler_task_call add column protocol varchar(8); add column tcp_ok boolean`（H2 用 `add column if not exists`；参照既有 V2/V3 写法）
- [x] 5.3 `DatabaseInitializerTest` 或新测试确认 5 方言迁移后 call 表含 `protocol`/`tcp_ok` 列且既有 API 记录的该两列为 null
- [x] 5.4 既有 `SchedulerTaskCallStore` 相关测试补 `protocol`/`tcp_ok` 断言，转绿

## 6. Diff 服务按类型比对

- [x] 6.1 `SchedulerTaskDiffService.diff()` 配置体比对按 `taskType` 分流：API 走既有 method/url/headers/body/assertion/timeouts；TCP 走 host/port/timeouts；标量与 notifyConfigIds 比对对所有类型生效
- [x] 6.2 `isEmptyConfig` 按类型分流判断（TCP：host/port 空即视为空）
- [x] 6.3 写 Diff 测试：TCP host 改动被识别、host/port 完全一致不误报；API 既有比对回归不破

## 7. 通知变量补充 protocol

- [x] 7.1 `SchedulerNotifier.RenderContext` 新增 `protocol` 字段；`SchedulerEngine.dispatchNotifications` 传入（API→"API"，TCP→"TCP"）
- [x] 7.2 `SchedulerNotifier.systemVars` 暴露 `{{protocol}}`；TCP 下 `{{httpStatus}}`/`{{assertionPassed}}` 为空字符串
- [x] 7.3 写 Notifier 测试：TCP 失败渲染 `{{protocol}}=TCP`；既有 API 渲染回归

## 8. 前端表单分类型

- [x] 8.1 `SchedulerTasks.vue`：`taskType` 下拉新增 `TCP` 选项；编辑态禁用类型下拉（D7 不可变）
- [x] 8.2 表单按 `form.taskType` `v-if` 切换 API 区块（method/url/headers/body/断言，既有）与 TCP 区块（host input + port input-number 1-65535 + 连接超时）
- [x] 8.3 `buildConfig()` 按 taskType 构造 `ApiTaskConfig`（带 `type=API`）或 `TcpTaskConfig`（带 `type=TCP`）；`openEdit`/`openCopy` 按源任务类型预填
- [x] 8.4 复制按钮预填按源任务类型对应字段（TCP 预填 host/port/超时），名称 `_copy`
- [x] 8.5 `TaskCalls.vue`：列表新增「协议」列；API 行显示 method+url+httpStatus，TCP 行显示 host:port + tcp_ok 徽标
- [x] 8.6 `npm run type-check && npm run build` 转绿

## 9. 文档与收尾

- [x] 9.1 更新 `docs/design/12-scheduled-tasks.md`：新增 TCP 任务类型章节、TaskTargetGuard 扩展点说明、安全一节补「出站目标当前无限制，依赖 MANAGE 权限，可配置防护见后续 change」
- [x] 9.2 后端全量测试：`mvn -pl backend -am test -Dskip.frontend=true` 转绿
- [x] 9.3 一体打包验证：`mvn -pl backend -am package`（含前端构建）通过
- [x] 9.4 输出中文变更摘要、测试结果、未完成风险（SSRF 可配置防护拆到 `add-task-target-guard`）
