# Implementation Tasks - scheduler-robustness-controls

> TDD：先补测试再实现。后端 `mvn -pl backend -am test -Dskip.frontend=true`；前端 `cd frontend && npm run type-check && npm run build`。

## 1. 超时配置（后端）

- [x] 1.1 `ApiTaskConfig` 增 `Timeouts{connect,read,write}`（秒，Integer，null=用默认 connect5/read30/write30）；diff 覆盖超时字段
- [x] 1.2 `ApiTaskHttpExecutor` 支持按任务超时执行：连接超时按值缓存 HttpClient；读/写超时用请求级 timeout；默认连接 5s、读/写 30s
- [x] 1.3 超时分类捕获（`HttpTimeoutException`/`ConnectException`）-> 标注「连接超时/读超时」-> 写调用记录 error + 审计 `scheduler.task.timeout`
- [x] 1.4 单元测试：默认超时、自定义超时、连接超时断开、读超时断开、超时入日志

## 2. 超时配置（前端）

- [x] 2.1 任务编辑表单增加连接/读/写超时输入项（默认连接 5、读/写 30，0=不限）；openEdit/load 带出
- [x] 2.2 buildConfig 携带 timeouts；复制任务一并带出
- [x] 2.3 `npm run type-check` 通过

## 3. 调用记录分页（后端）

- [x] 3.1 `SchedulerTaskCallStore` 新增 `findPage(taskIds, from, to, success, page, size)` 返回 `{items,total}`（limit/offset + count）
- [x] 3.2 `SchedulerTaskCallController` 单任务/全部任务查询支持 `page`+`size`，返回 `{items,total,page,size}`；旧 `limit` 兼容
- [x] 3.3 单元测试：分页返回正确页/total、边界（超出页码返回空）

## 4. 调用记录分页（前端）

- [x] 4.1 `TaskCalls.vue` 改用分页查询（page/size），底部 `el-pagination`
- [x] 4.2 切换任务/过滤条件重置到第 1 页
- [x] 4.3 `npm run type-check` + build 通过

## 5. 通知配置：URL 脱敏 + 明文查看

- [x] 5.1 `SchedulerNotifyConfigController.toView` 默认脱敏 URL（隐藏 `?` 后 query 段）
- [x] 5.2 新增 `GET /{id}/plain-url`（仅 owner，`getOrThrow` 校验）返回明文
- [x] 5.3 前端列表/编辑页脱敏显示 + 小眼睛按钮调 plain-url 展开明文
- [x] 5.4 单元测试：脱敏逻辑、明文端点 owner 校验

## 6. 通知配置：启停 + 保留绑定

- [x] 6.1 `SchedulerNotifyConfigController` 新增 `POST /{id}/disable`、`/enable`（仅 owner）
- [x] 6.2 引擎 `shouldFire` 已判 enabled（确认）；禁用即不派发，绑定关系不动
- [x] 6.3 前端列表/详情增加禁用/恢复按钮，展示启停状态
- [x] 6.4 集成测试：禁用后不派发、绑定保留、恢复后派发

## 7. 验证与收尾

- [x] 7.1 端到端：超时（慢 mock）-> 失败 + 日志；分页翻页；URL 脱敏+小眼睛明文；通知禁用/恢复
- [x] 7.2 安全：超时/URL 明文不泄露到非 owner；权限点覆盖
- [x] 7.3 文档：更新 `docs/design/12-scheduled-tasks.md`（超时/分页/通知脱敏启停）
- [x] 7.4 全量验证：后端全测、`mvn -pl backend -am package`、前端构建通过

## 8. 补强（迭代收尾）

- [x] 8.1 `publish()` 复位 `enabled=true`（停用后直接发布即可运行，无需再点启停）；回归测试
- [x] 8.2 状态机解耦：`setEnabled` 不再改 `status`（去掉冗余 DISABLED），`status` 只表生命周期（DRAFT/PUBLISHED），`enabled` 是唯一启停门控；列表「发布状态」「启停」分两列展示
- [x] 8.3 通知配置 URL 脱敏值不覆盖明文：`update()` 收到 `?***` 形式时跳过 url 更新，保留库中真实 URL
- [x] 8.4 小眼睛按钮改为切换（明文↔脱敏），记住脱敏前值；列表与编辑表单两处
- [x] 8.5 定时任务 `toggleEnabled` 前端改用 `enabled` 判断（配套状态机解耦）
- [x] 8.6 通知配置页增加搜索（名称/方法/地址/触发时机）
- [x] 8.7 ApiKey 轮换弹窗重排：key + 复制图标按钮横向并排，footer 双按钮
