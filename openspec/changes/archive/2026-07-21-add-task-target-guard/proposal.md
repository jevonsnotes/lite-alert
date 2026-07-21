## Why

定时任务（API / TCP）由服务端代为发起出站连接，用户可填任意 URL 或 `host:port`。这构成 SSRF 与内网端口扫描风险——服务端可能被用来探测内网拓扑、访问云元数据端点（`169.254.169.254`）、连接内网管理后台或数据库端口。`add-tcp-task-type` 已埋好出站目标防护扩展点 `TaskTargetGuard`（默认放行），本 change 接上它的真正实现：一个**可配置、热生效、API+TCP 共用**的出站目标防护。

## What Changes

- 实现 `TaskTargetGuard` 的真实 bean，替换 `add-tcp-task-type` 引入的默认放行实现 `AllowAllTaskTargetGuard`：在 API / TCP 任务发起实际连接前，对出站目标（host:port）做地址校验，不通过则抛 `BusinessException`、写调用记录失败 + 审计。
- 防护配置接入既有 `SystemSettings`（`la_system_settings` JSON）：新增 `TaskTargetGuardConfig`（开关 + 拦截网段黑名单 + 可选显式允许列表），复用 `SystemSettingsService` 的 `AtomicReference` 内存缓存与 `current()/save()`，**改完即热生效**，无需重启。
- 默认策略：**默认放行**（`enabled=false`），与「不破坏既有可用性」对齐；管理员按需开启并配置拦截网段。
- 地址解析：API 任务从配置 URL 解析 host/port（默认端口按协议补 80/443）；TCP 任务直接用 `host:port`。host 先解析为 IP，再按 CIDR 网段匹配（覆盖 IPv4 私有/回环/链路本地/元数据地址等内置网段）。
- 内置常见拦截网段常量（`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`127.0.0.0/8`、`169.254.0.0/16` 等），管理员可在配置里增删。
- 前端「系统设置」页新增「出站目标防护」区块：开关 + 拦截网段列表编辑 + 显式允许列表编辑。
- 审计：拦截发生时记 `scheduler.task.target-blocked`（含 host/port/命中的网段，不记敏感信息）。

## Capabilities

### New Capabilities
- `task-target-guard`: 定时任务出站目标的可配置地址防护（开关 + 网段黑/白名单 + 热生效），API 与 TCP 任务共用。

### Modified Capabilities
- `tcp-task-runner`: `出站目标防护扩展点` requirement 从「默认放行」升级为「按 `task-target-guard` 配置的真实防护」（默认仍放行，开启后拦截）。
- `api-task-runner`: 新增 API 任务连接前同样受 `task-target-guard` 约束（与 TCP 对称）。

## Impact

- **后端域**：`io.litealert.scheduler` 实现 `TaskTargetGuard` 真实 bean（网段解析、CIDR 匹配）；`io.litealert.admin.settings` 的 `SystemSettings` 新增 `TaskTargetGuardConfig`；`SystemSettingsController` 暴露新字段。
- **复用**：复用 `SystemSettingsService` 的内存缓存与热更新机制（无需新建配置存储/轮询）；复用 `ApiTaskHttpExecutor`/`ApiTaskTcpExecutor` 既有的连接前 `guard.check` 调用点（由 `add-tcp-task-type` 已埋好）。
- **依赖**：无新增三方依赖，CIDR 匹配可用 JDK `InetAddress` 自行实现或轻量工具。
- **DB**：无 schema 变更（配置存进既有 `la_system_settings.settings_json`）。
- **权限**：复用既有系统设置管理权限，不新增。
- **安全**：防护本身是安全增强；注意 DNS rebinding 风险——解析与连接之间存在 TOCTOU 窗口，本版接受该残余风险（探活场景影响有限），design.md 记录。
- **依赖关系**：**依赖 `add-tcp-task-type` 落地**（消费其 `TaskTargetGuard` 接口与连接前调用点）。
