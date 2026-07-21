# Design — scheduler-robustness-controls

## Context

三项健壮性/可用性补强：①HTTP 超时硬编码（连接 5s/请求 30s）不可配且超时无日志；②调用记录无分页；③通知配置 URL 明文暴露、缺启停控制。均在不破坏既有双轨/owner 私有模型前提下增量补齐。

## Goals / Non-Goals

**Goals:**
- 任务可配连接/读/写超时（连接默认 5s，读/写默认 30s，0=不限），双轨生效，超时断开 + 日志。
- 调用记录分页（page/size），单任务 + 全部任务。
- 通知配置 URL 脱敏 + owner 明文查看 + 启停（禁用不派发但保留绑定）。

**Non-Goals:**
- 不做通知配置跨 owner 共享（仍 owner 私有）。
- 不引入新权限点（明文查看 = owner，见决策 D4）。
- 不做请求重试。

## Decisions

### D1：超时配置纳入 `ApiTaskConfig`，随双轨

**选择**：`ApiTaskConfig` 增 `Timeouts{connect,read,write}`（秒，Integer，null=用默认：connect 5、read 30、write 30）。任务级超时随 config 走草稿/发布双轨；引擎执行时读已发布 config 的超时。`Meta` 无需额外快照（超时在 config 内，已随 config 快照）。

**理由**：超时是 HTTP 请求属性，归属 config 而非任务标量；复用既有双轨无需新机制。

### D2：`ApiTaskHttpExecutor` 支持按任务超时 + 超时分类日志

**选择**：`execute(config)` 读 `config.getTimeouts()`，为每次请求构建带指定超时的 `HttpRequest`（`connectTimeout` 走新建 `HttpClient` 或保留全局 + 请求级 `timeout` 覆盖读/写）。`java.net.http.HttpClient` 的 connectTimeout 是客户端级、不可单请求改——所以**按任务的连接超时需为每个不同超时值复用/缓存 HttpClient**，或退化为「请求级 timeout 取三者最小值」+ 全局 connectTimeout。

**务实方案**：连接超时用「按超时值缓存 HttpClient」（少数几个值），读/写超时用请求级 `.timeout()`（`HttpRequest` 的 timeout 覆盖整体请求时限 = max(read,write) 的合理近似）。超时异常（`HttpTimeoutException`/`ConnectException`）分类标注「连接超时/读超时」，写入调用记录 error + 审计 `scheduler.task.timeout`。

**备选**：保持单一全局 HttpClient，只做请求级 timeout——简单但无法单独配连接超时。弃用（用户明确要连接/读/写分开）。

### D3：调用记录分页

**选择**：Store 新增 `findPage(taskIds/taskId, from, to, success, page, size)` 返回 `{items, total}`，SQL 用 `limit ? offset ?` + 单独 `count`。Controller 接收 `page`(默认1)+`size`(默认20，上限100)，返回 `{items,total,page,size}`。旧 `limit` 参数保留兼容但标记 deprecated。

**理由**：H2/MySQL/PG 均支持 `limit/offset`，跨库一致。total 单独 count（记录量大时 count 有开销，但调用记录量级可控）。

### D4：通知配置 URL 脱敏 + owner 明文查看

**选择**：`SchedulerNotifyConfigController.toView` 默认脱敏 URL（隐藏 `?` 后 query 段，如 `https://oapi.dingtalk.com/robot/send?access_token=***`）。新增 `GET /{id}/plain-url` 端点，**仅 owner**（`getOrThrow` 已校验 owner）可调，返回明文。前端脱敏 URL 旁加「小眼睛」按钮调该端点展开明文。

**理由**：通知配置 owner 私有，能查配置即 owner，故明文查看=owner 即可，**不新增权限点**（你确认的方案）。

### D5：通知配置启停 + 保留绑定

**选择**：复用既有 `enabled` 字段（Store 已存）。新增 `POST /{id}/disable`、`POST /{id}/enable` 端点（仅 owner）。引擎 `dispatchNotifications` 在 `shouldFire` 里已判 `enabled`（既有逻辑），禁用即不派发。**绑定关系不动**——`notifyConfigIds` 仍含禁用配置的 id，恢复 enabled=true 后自动恢复派发。

**理由**：`enabled` 字段与 `shouldFire` 已存在，只需暴露启停端点 + 前端按钮；绑定关系天然保留（不删配置、不改 notifyConfigIds）。

## Risks / Trade-offs

- **[HttpClient 连接超时按值缓存]** → 实际超时值种类有限（多数用默认 5），缓存池很小；若担心可退化为全局 connectTimeout + 请求级 timeout。首期实现按值缓存。
- **[分页 count 开销]** → 调用记录量级可控；后续量大可加近似 count 或游标分页（Non-Goal）。
- **[URL 脱敏可能误伤无 query 的地址]** → 仅对含 `?` 的脱敏 query 段，无 query 原样返回。
- **[MODIFIED delta 准确性]** → 已完整复制「通知配置生命周期」原需求并追加启停场景。

## Migration Plan

- 纯增量：超时随 config JSON（无新列）；分页走查询参数；通知配置 `enabled` 已有列。
- 无 DB 迁移、无新权限点。
- 部署即生效；旧任务无超时配置 → 用默认（连接 5s、读/写 30s）；旧通知配置 enabled 默认 true。

## Open Questions

- 超时默认值：连接 5s、读/写 30s（用户确认，采纳）。
- 分页 size 上限 100 是否合适？（首期 100，可调。）

## 范围补强（迭代收尾）

实现与验证过程中按反馈补强：

### D6：状态机解耦（去掉冗余 DISABLED）

**问题**：`status`（DRAFT/PUBLISHED/DISABLED）与 `enabled` 两个字段冗余表达"是否运行"，且 `setEnabled` 会把 status 改成 DISABLED，导致列表两列重复、publish 后不运行等连锁 bug。

**修复**：`status` 只表生命周期（DRAFT/PUBLISHED），`enabled` 是唯一启停门控。`setEnabled` 只 flip enabled 不动 status；`isSchedulable()` = `status==PUBLISHED && enabled`。列表「发布状态」「启停」分两列独立展示。`publish()` 复位 `enabled=true`（停用后直接发布即可运行）。enum 保留 DISABLED 兼容旧数据但新逻辑不再产生。

### D7：通知配置 URL 脱敏值不覆盖明文

**问题**：编辑通知配置时表单 url 是脱敏值（`?***`），未点小眼睛就保存会用脱敏值覆盖真实 URL，导致发送通知失败。

**修复**：后端 `update()` 收到 url 为脱敏形式（`endsWith("?***")`）时跳过 url 更新，保留库中明文。这是脱敏语义的必然——脱敏值不是真实数据，不该当真实数据存。前端小眼睛改为切换（明文↔脱敏），记住脱敏前值。

### D8：列表搜索与 ApiKey 弹窗重排

通知配置页增加搜索（名称/方法/地址/触发时机）。ApiKey 轮换弹窗重排：key + 复制图标按钮横向并排，footer 双按钮，排版整洁。
