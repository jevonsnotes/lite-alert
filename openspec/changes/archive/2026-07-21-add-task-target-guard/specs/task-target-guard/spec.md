# task-target-guard Delta

## ADDED Requirements

### Requirement: 出站目标防护配置

系统 SHALL 在系统设置中提供定时任务出站目标防护配置，包含：启用开关（`enabled`，默认关闭=放行）、拦截网段列表（`blockedCidrs`，CIDR 表示）、显式允许网段列表（`allowedCidrs`，CIDR 表示，优先级高于 `blockedCidrs`）。配置 SHALL 持久化进既有系统设置存储，且 SHALL 在保存后立即热生效，无需重启服务。

系统 SHALL 为 `blockedCidrs` 提供内置默认网段，覆盖 IPv4 私有网段、回环、链路本地与全零地址。

#### Scenario: 默认配置放行

- **WHEN** 系统初始化且管理员未修改防护配置
- **THEN** 出站目标防护开关为关闭
- **AND** `blockedCidrs` 含内置默认私有/回环/链路本地网段
- **AND** 任意出站目标均不被拦截

#### Scenario: 配置保存后立即生效

- **WHEN** 管理员在系统设置页开启防护并保存
- **THEN** 新配置立即对所有后续任务执行生效
- **AND** 无需重启服务

#### Scenario: 允许网段覆盖拦截网段

- **WHEN** 防护开启，某 IP 同时命中 `blockedCidrs` 与 `allowedCidrs`
- **THEN** 系统放行该目标（`allowedCidrs` 优先）

#### Scenario: 非法 CIDR 被剔除而非阻断保存

- **WHEN** 管理员保存的拦截网段列表中含无法解析的非法 CIDR
- **THEN** 系统剔除该非法 CIDR 并记录日志
- **AND** 其余合法 CIDR 正常保存

### Requirement: 出站目标地址校验

当防护开启时，系统 SHALL 在 API 与 TCP 任务发起实际连接前，对出站目标的 IP 地址做网段校验：将 host 解析为全部 IP，若任一 IP 命中 `blockedCidrs` 且未被 `allowedCidrs` 覆盖，SHALL 拒绝连接。API 任务 SHALL 从配置 URL 解析 host/port（端口缺失时按协议默认补）；TCP 任务 SHALL 直接使用配置的 host/port。

被拦截的目标 SHALL 被判定为执行失败，SHALL 写一条失败调用记录（含命中的拦截网段信息），且 SHALL 记审计 `scheduler.task.target-blocked`。

#### Scenario: TCP 任务连内网端口被拦截

- **WHEN** 防护开启，一个 TCP 任务的目标 IP 命中默认拦截私有网段（如 `10.0.0.5:3306`）
- **THEN** 系统不发起 TCP 连接
- **AND** `protocol` 为 `TCP`、`tcpOk` 为 false、状态为失败
- **AND** `error_message` 含命中网段
- **AND** 审计记录 `scheduler.task.target-blocked`

#### Scenario: API 任务连云元数据被拦截

- **WHEN** 一个 API 任务的目标 `169.254.169.254` 命中链路本地网段
- **THEN** 系统不发起 HTTP 请求
- **AND** `protocol` 为 `API`、状态为失败

#### Scenario: 命中允许网段的目标放行

- **WHEN** 一个 TCP 任务的目标 `10.0.0.5` 同时在 `blockedCidrs`（默认私有网段）与 `allowedCidrs`
- **THEN** 系统放行并正常发起连接

#### Scenario: host 解析失败不视为拦截

- **WHEN`UnknownHostException`）
- **THEN** 系统不当作目标拦截
- **AND** 交由后续连接执行按连接失败处理

### Requirement: 防护默认实现退场

当系统中存在真实的出站目标防护实现时，默认放行实现 SHALL 自动退场（不装配），使防护实现零配置接管扩展点调用。当且仅当不存在真实实现时，默认放行实现 SHALL 生效。

#### Scenario: 引入真实防护后默认放行退场

- **WHEN** 系统中存在真实的出站目标防护实现（`CidrTaskTargetGuard`）
- **THEN** 默认放行实现（`AllowAllTaskTargetGuard`）不被装配
- **AND** `guard.check` 由真实实现承接
