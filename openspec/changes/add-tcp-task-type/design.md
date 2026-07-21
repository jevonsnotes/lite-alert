## Context

定时任务域 `io.litealert.scheduler` 首期已落地 `API` 任务类型：`SchedulerTask` 持有 `draftConfig`/`publishedConfig` 两个 `ApiTaskConfig` JSON 列，`SchedulerEngine` 用 `ApiTaskHttpExecutor` 发 HTTP、用 `WebhookResponseAssertor` 判定、写 `SchedulerTaskCall` 记录、派发通知。设计文档 `docs/design/12-scheduled-tasks.md` 与 spec `scheduled-task-management` 已把 `task_type` 列作为「扩展点」预留，但配置类、Store、Engine、Diff、前端表单都写死在 `ApiTaskConfig`/HTTP 语义上。

本次兑现该扩展点新增 `TCP` 任务类型。核心形态为**连通性探活**（形态 A）：按 Cron 对 `host:port` 建 TCP 连接，连接成功=成功，失败/超时/拒绝=失败。不发 payload、不读响应、无响应断言。

约束（来自项目规范与既有设计）：
- 定时任务功能**尚未发版**，可直接清理旧数据，无需为无鉴别字段的旧 JSON 做反序列化兼容。
- 复杂字段以 JSON 文本存储，DB 表结构尽量稳定。
- 后端按业务域分包、Store 模式、构造器注入、record 请求体。
- 5 个数据库方言（h2/mysql/postgresql/gaussdb/oceanbase）的迁移脚本必须同步。
- 无新增三方依赖；TCP 探活用 JDK `java.net.Socket`。

## Goals / Non-Goals

**Goals:**
- 新增 `TCP` 任务类型，与 `API` 共用调度/双轨/调用记录/通知链路，仅替换「执行 + 判定」。
- 配置载体多态化（`TaskConfig` 基类 + `ApiTaskConfig`/`TcpTaskConfig` 子类），使后续新增类型无需再改公共骨架。
- `SchedulerEngine.run()` 按类型路由执行器，TCP 任务不进入 HTTP 断言路径。
- 调用记录表能同时承载 API 与 TCP 两种结果语义。
- 前端按 `taskType` 切换表单区块，调用列表按 `protocol` 展示。

**Non-Goals:**
- TCP 请求/响应探活（形态 B：发 payload + 读响应 + 响应断言）。本版仅连通性。`TcpTaskConfig` **不预留**形态 B 字段位，未来加形态 B 再扩展。
- **SSRF / 出网限制的可配置防护**：本 change 仅埋一个 `TaskTargetGuard` 扩展点（接口 + 默认放行实现），真正的「可配置黑/白名单 + 开关」防护拆到独立 change `add-task-target-guard`（配置存 DB 系统设置表，默认放行，API+TCP 共用）。见 D10。
- TCP 失败重试、最小触发间隔、告警联动（与 API 任务同样不在范围）。
- TCP over TLS / UDP / ICMP 探活。
- 多实例调度（仍内存调度，ShedLock 非目标）。
- 既有 API 任务数据的兼容迁移（直接清理重建）。

## Decisions

### D1. 配置多态：基类 `TaskConfig` + Jackson `@JsonTypeInfo` 鉴别

引入抽象基类 `TaskConfig`，携带类型鉴别字段 `type`（API/TCP），并把双轨快照用的 `Meta` 从 `ApiTaskConfig` 上提到基类：

```
TaskConfig (abstract)
 ├─ type: String          // @JsonTypeInfo 鉴别字段 (PROPERTY "type")
 ├─ meta: Meta            // 上提：发布快照的 name/desc/cron/notifyConfigIds
 └─ timeouts: Timeouts    // 上提：API 与 TCP 都需要连接超时
     ├─ ApiTaskConfig     // method/url/headers/body/assertion
     └─ TcpTaskConfig     // host/port
```

- 鉴别用 `@JsonTypeInfo(use=NAME, include=PROPERTY, property="type")` + `@JsonSubTypes({API, TCP})`。
- `Meta` 与 `Timeouts` 上提到基类：二者对两种类型都适用（TCP 需要 `connect` 超时；`read/write` 对 TCP 无意义但保留字段不碍事，避免基类再拆）。`Timeouts` 的 `effectiveConnect()` 对 TCP 即连接超时；`read/write` TCP 执行器忽略。
- **为何不用「独立类型 + Store 运行时分流」**：那样 `SchedulerTask.draftConfig` 字段类型仍是 `ApiTaskConfig`，TCP 任务无处安放；多态基类让单一字段承载任意类型，Store 一次 `read(json, TaskConfig.class)` 即可分流，扩散面更小。
- **为何不为旧数据做兼容**：定时任务未发版，旧 `ApiTaskConfig` JSON 无 `type` 字段；与其给 `@JsonTypeInfo` 配 `defaultImpl` 处理混合数据，不如直接清理（drop 既有 `la_scheduler_task` 行 + 重建 call 表），数据干净。迁移脚本里旧 V2/V3 保持不动，新增 V4 处理结构变化。

### D2. `Meta` 上提的影响面

`Meta` 原挂在 `ApiTaskConfig`，被 `SchedulerEngine`（读 `publishedConfig.getMeta().getCron()`/`getNotifyConfigIds()`）和 `SchedulerTaskDiffService`（比对 published meta 与 draft 行）依赖。上提到 `TaskConfig` 后：
- `SchedulerTaskService.publish()` 改为在 `TaskConfig` 上 `setMeta(...)`（基类方法）。
- `SchedulerEngine.schedule()` 读 `task.getPublishedConfig().getMeta()` 不变（基类暴露）。
- `SchedulerTaskDiffService` 标量比对逻辑不变。
- 这一步是 D1 的必然结果，单独列出是因为它是 `publish` 快照链路的关键，必须保证「发布时 meta 写进 publishedConfig，引擎只读 publishedConfig.meta」的不变量不被破坏。

### D3. 执行器路由：`SchedulerEngine.run()` 按 `taskType` 分流

`SchedulerEngine` 现在硬编码 `httpExecutor.execute(config)` + 断言。改造为：

```
run(taskId):
  task = findById
  config = task.getPublishedConfig()          // TaskConfig
  switch (task.getTaskType()):
    API  -> runApi(task, config as ApiTaskConfig)   // 既有 HTTP + 断言逻辑，原样保留
    TCP  -> runTcp(task, config as TcpTaskConfig)   // 新增
```

- 不引入 `Map<Type, Executor>` 注册表：当前仅两种类型，switch 直读更清晰；待第三种类型出现再抽象 `TaskExecutor` 接口。这是 YAGNI 边界。
- `runTcp`：调 `ApiTaskTcpExecutor.execute(tcpConfig)` -> 连接成功则 `success=true`、`tcpOk=true`、`httpStatus=null`、`excerpt=null`；捕获 `SocketTimeoutException`/`ConnectException`/`UnknownHostException` 等分类写 error + 审计，与 `runApi` 的异常分类风格一致。
- **复用判定**：TCP 不走 `WebhookResponseAssertor`（无响应体），`assertionPassed` 恒为 null（表示「无断言，按连通性判定」）。

### D4. 新建 `ApiTaskTcpExecutor`（JDK Socket）

```java
@Component
public class ApiTaskTcpExecutor {
    // 每次新建 Socket，不复用连接池（探活场景：短连接、低频）
    public Result execute(TcpTaskConfig config) throws Exception {
        int connectSec = effectiveConnect(config.getTimeouts());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.getHost(), config.getPort()),
                           Duration.ofSeconds(connectSec).toMillis());
            return new Result(true, null);   // 连接建立即成功
        }
    }
    public record Result(boolean connected, String error) {}
}
```

- 连接超时取 `Timeouts.connect`（默认 5s）；`read/write` 对 TCP 探活无意义，忽略。
- 不发数据、不读响应：连接 `connect()` 返回即判定成功，立即关闭。避免对端把探活连接当真实流量处理。
- 命名沿用 `ApiTaskHttpExecutor` 的 `ApiTask` 前缀习惯（`ApiTaskTcpExecutor`），保持包内命名一致性；若后续要抽象执行器接口再统一改名。
- `new Socket()` 不缓存：探活是低频（Cron 周期）短连接，连接池收益为零反而增复杂度。

### D5. 调用记录表扩列：新增 `protocol` + `tcp_ok`

`la_scheduler_task_call` 现有列 `method/url/http_status` 对 TCP 无意义。**扩列而非另建表**：

| 新列 | 类型 | API 任务 | TCP 任务 |
|------|------|----------|----------|
| `protocol` | varchar(8) | `'API'` | `'TCP'` |
| `tcp_ok` | boolean | null | true/false |

- `method/url/http_status` 对 TCP 写 null（列本就可空）。
- `assertion_passed` 对 TCP 恒 null（无断言）。
- `response_excerpt` 对 TCP 恒 null（无响应体）。
- `error_message` / `duration_ms` / `success` 两种类型通用。
- **为何不另建 `la_scheduler_tcp_call` 表**：仪表盘统计、通知派发、分页查询都按 `task_id` 聚合，分表会让 `SchedulerTaskCallStore` 的所有 `countByTasks`/`dailyTrend`/`findPage` 都要 UNION，得不偿失。扩列 + `protocol` 区分是最小改动。
- 迁移方式（V4，5 方言）：`alter table la_scheduler_task_call add column protocol ...; add column tcp_ok ...`。因未发版，也可直接 drop+重建 call 表，但 alter 更稳、更易回滚。两种都可行，倾向 alter。

### D6. Diff 服务按 `taskType` 比对

`SchedulerTaskDiffService` 现全程按 `ApiTaskConfig` 字段比对。改造：
- 标量比对（name/desc/cron/notifyConfigIds）不变（基于 `Meta`）。
- 配置体比对：`switch(taskType)` -> API 走既有 method/url/headers/body/assertion/timeouts 比对；TCP 走 host/port/timeouts 比对。
- `isEmptyConfig` 同样按类型分流判断。
- 跨类型编辑（taskType 不可改，见 D7），不出现「draft 是 TCP、published 是 API」的混合比对。

### D7. `taskType` 创建后不可变

`taskType` 在创建时由 `SchedulerTaskTypeClassifier` 校验并持久化。**编辑/保存草稿不允许改 taskType**（前端下拉禁用，后端 `saveDraft` 忽略请求中的 taskType）。理由：类型决定 config 多态反序列化与执行器路由，跨类型改写会让 draft/published 的 config 类型不一致，Diff 与发布语义崩坏。需要换类型=删旧建新。

### D8. 前端表单按类型切换区块

`SchedulerTasks.vue` 现在表单全是 API 字段。改造为两个区块，按 `form.taskType` `v-if` 切换：
- `taskType` 下拉新增 `TCP` 选项；编辑态禁用（D7）。
- API 区块：method/url/headers/body/断言（既有，原样保留）。
- TCP 区块：host（input）+ port（input-number 1-65535）+ 连接超时（复用 `Timeouts.connect`）。
- `buildConfig()` 按 taskType 构造 `ApiTaskConfig` 或 `TcpTaskConfig`（带 `type` 鉴别字段）。
- `TaskCalls.vue` 列表：`protocol` 列；API 行显示 method+url+httpStatus，TCP 行显示 host:port + tcp_ok 徽标。

### D9. 通知变量补充 `protocol`

`SchedulerNotifier.RenderContext` 新增 `protocol`（"API"/"TCP"），`systemVars` 暴露 `{{protocol}}`。TCP 任务下 `{{httpStatus}}`/`{{assertionPassed}}` 留空字符串。通知模板可按 protocol 区分文案。其余通知链路（双轨绑定、triggerOn、派发）不变。

### D10. 出站目标防护扩展点 `TaskTargetGuard`（埋点，默认放行）

出站目标（API 的 URL / TCP 的 host:port）存在 SSRF 与内网扫描风险。**本 change 不实现防护逻辑**，仅埋一个扩展点，让后续独立 change `add-task-target-guard` 能零侵入接入：

```java
public interface TaskTargetGuard {
    /** 校验出站目标是否允许连接；不允许则抛 BusinessException。默认实现全放行。 */
    void check(String host, int port);
}
```

- 默认 bean `AllowAllTaskTargetGuard`（`check` 空实现），`runApi`/`runTcp` 在发起连接前调用 `guard.check(host, port)`。
- API 任务：从 `URL` 解析 host/port（默认端口按协议补 80/443）后调 guard。
- TCP 任务：直接用 config 的 host/port 调 guard。
- **接口形状对齐未来实现**：`add-task-target-guard` 计划把配置存 DB 系统设置表（开关 + 黑/白名单网段），默认放行，API+TCP 共用一个 guard bean。本 change 把 `check(host, port)` 的签名定好，未来实现只需替换 bean，不改调用点。
- 这样拆的理由：SSRF 可配置防护（配置存储、热更新、前端管理页、网段解析）工作量足以独立成 change，混进 TCP 类型会破坏 change 内聚。

## Risks / Trade-offs

- **[多态反序列化失败风险]** Jackson `@JsonTypeInfo` 对未知 `type` 值会抛异常 -> Store.map 读取整行失败。**Mitigation**: `SchedulerTaskStore.parseType` 已有 try-catch 容错；`map()` 里 `json.read(..., TaskConfig.class)` 同样包 try-catch，失败回退为 null（任务不调度）并记日志，不拖垮启动恢复。
- **[Meta 上提的回归面]** `Meta` 从 `ApiTaskConfig` 移到 `TaskConfig`，所有 `getMeta()` 调用点（Engine/Diff/Service）都要改 import/类型。**Mitigation**: 字段名与方法名不变，多数是改声明类型；TDD 先补 `publish` 快照 + Engine 读 meta + Diff 标量比对的测试再动代码。
- **[Socket 资源泄漏]** 探活连接若 `connect` 成功但后续异常未关 -> fd 泄漏。**Mitigation**: try-with-resources 包 `Socket`；不读不写，连接即关。
- **[host/port 输入安全（SSRF）]** 用户可填任意 host/port，可能被用于内网端口扫描 / 云元数据访问。**Mitigation**: 本 change 埋 `TaskTargetGuard` 扩展点（D10，默认放行），真正可配置防护（黑/白名单网段 + 开关，存 DB 系统设置表，默认放行，API+TCP 共用）拆到独立 change `add-task-target-guard`。本 change 至少保证 port 1-65535、host 非空的基本校验。
- **[DB 迁移方言差异]** 5 方言的 `alter table add column` 语法、boolean 类型、可空列默认值有细微差异。**Mitigation**: 参照既有 V2/V3 迁移的写法逐方言手写；H2 用 `add column if not exists`，其余用标准 `add column`。
- **[扩列 vs 重建 call 表]** 扩列让 `tcp_ok` 对 API 行为 null，统计 SQL 要注意 null 语义。**Mitigation**: 统计只用 `success` 列（两种类型都写），`tcp_ok`/`http_status` 仅展示用，不进聚合。

## Migration Plan

1. **后端代码**：先建 `TaskConfig`/`TcpTaskConfig`/`ApiTaskTcpExecutor`/`TaskTargetGuard`，改 `SchedulerTask`/Store/Engine/Service/Diff/Notifier（TDD）。
2. **DB 迁移**：5 方言各加 `V4__scheduler_tcp_call_columns.sql`，`alter table la_scheduler_task_call add column protocol varchar(8), add column tcp_ok boolean`。因未发版，若本地有测试数据直接清空 call 表即可。
3. **前端**：表单分区块 + 调用列表 protocol 列。
4. **回滚**：未发版无线上数据，回滚=还原代码 + drop 新增列（`alter table ... drop column protocol, drop column tcp_ok`）。
5. **验证**：`mvn -pl backend -am test -Dskip.frontend=true`；前端 `npm run type-check && npm run build`。
6. **后续 change**：`add-task-target-guard` 实现 `TaskTargetGuard` 的可配置防护（配置存 DB 系统设置表，默认放行，API+TCP 共用）。

## Open Questions

- ~~Q1 SSRF/出网限制~~ -> 已定：**一起加防护且可配置化**，但拆成独立 change `add-task-target-guard`（配置存 DB 系统设置表，默认放行，API+TCP 共用）。本 change 仅埋 `TaskTargetGuard` 扩展点（D10）。
- ~~Q2 形态 B 预留字段位~~ -> 已定：**不需要形态 B**，`TcpTaskConfig` 不预留 payload/expectedResponse 字段。
- ~~Q3 人类可读摘要~~ -> 已定：**写**。TCP 成功时 `response_excerpt` 写 `connected to host:port in Xms`。
