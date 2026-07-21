## Context

`add-tcp-task-type` 已埋好出站目标防护扩展点 `TaskTargetGuard`（接口 `check(host, port)` + 默认放行实现 `AllowAllTaskTargetGuard`），API/TCP 执行器在发起实际连接前都调用它。本 change 把该扩展点的真实实现接上：按可配置的网段规则拦截出站目标。

既有基础设施已验证可用，无需新建：
- `SystemSettings`（JSON blob POJO）+ `SystemSettingsService`（`AtomicReference` 内存缓存，`current()` 读 / `save()` 写 + 落库 + 审计）——天然支持**热生效**，改完即生效，无需重启。
- `la_system_settings` 单行 JSON 列存配置——**无 schema 变更**。
- `ErrorCode.INVALID_ARGUMENT`(400) 可复用于「目标被拦截」；`BusinessException` 抛出后由 `SchedulerEngine.run` 的既有 catch 写失败调用记录 + 审计。

风险面：定时任务由服务端代发起连接，用户可填任意 host:port/URL → SSRF、内网端口扫描、云元数据访问。

## Goals / Non-Goals

**Goals:**
- 实现 `TaskTargetGuard` 真实 bean，替换默认放行实现，对 API+TCP 出站目标做网段校验。
- 防护可配置（开关 + 拦截网段黑名单 + 显式允许列表）、热生效、存 `SystemSettings`。
- 默认放行，不破坏既有可用性。
- 前端系统设置页提供配置入口。
- 拦截行为可观测（调用记录失败 + 审计）。

**Non-Goals:**
- 完全消除 DNS rebinding / TOCTOU（接受残余风险，见 Risks）。
- IPv6 精细网段策略（本版以 IPv4 为主，IPv6 仅做回环等基础拦截）。
- 按任务/按租户粒度的差异化防护（全局一套配置）。
- 出站流量内容审查（只管目标地址）。

## Decisions

### D1. 配置模型 `TaskTargetGuardConfig` 挂进 `SystemSettings`

```java
public static class TaskTargetGuardConfig {
    private boolean enabled = false;                 // 默认放行
    private List<String> blockedCidrs = defaultBlockedCidrs();  // 拦截网段
    private List<String> allowedCidrs = List.of();   // 显式允许（覆盖 blockedCidrs）
}
// SystemSettings 新增: private TaskTargetGuardConfig taskTargetGuard = new TaskTargetGuardConfig();
```

- 内置默认拦截网段常量：`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`127.0.0.0/8`、`169.254.0.0/16`（链路本地/云元数据）、`0.0.0.0/8`。
- `allowedCidrs` 优先级高于 `blockedCidrs`：命中 allowed 即放行（支持「拦截私有网段但放行某个内网监控目标」的常见诉求）。
- **为何用既有 `SystemSettings` 而非新表**：复用热更新机制（`AtomicReference`）、复用 `SystemSettingsController` 暴露路径、零 schema 变更。与 `RateLimitConfig`/`Span` 等既有子配置同构。
- `SystemSettingsService.normalize()` 补 `TaskTargetGuardConfig` 的 null 兜底与 CIDR 合法性校验（非法 CIDR 在保存时被剔除并记日志，不阻断保存）。

### D2. 真实 `TaskTargetGuard` bean：地址解析 + CIDR 匹配

```java
@Component
@RequiredArgsConstructor
public class CidrTaskTargetGuard implements TaskTargetGuard {
    private final SystemSettingsService settings;

    public void check(String host, int port) {
        TaskTargetGuardConfig cfg = settings.current().getTaskTargetGuard();
        if (!cfg.isEnabled()) return;                          // 默认放行
        for (InetAddress addr : resolveAll(host)) {            // 一个 host 可能多 IP
            if (matchesAny(addr, cfg.getAllowedCidrs())) continue;   // allowed 覆盖
            if (matchesAny(addr, cfg.getBlockedCidrs())) {
                throw blocked(host, port, matchedCidr);         // -> BusinessException
            }
        }
    }
}
```

- 用 `InetAddress.getAllByName(host)` 解析全部 IP，任一命中 blocked（且未被 allowed 覆盖）即拦截。
- CIDR 匹配自实现轻量工具：`BigInteger` 做 IPv4 掩码包含判断（避免引第三方依赖）。CIDR 解析结果按 `enabled` 变化时重新构建一个不可变 `List<Cidr>` 缓存，避免每次 check 重复解析字符串。
- **为何不引 commons-net / ippool**：拦截规则简单（几十条 CIDR），自实现 ~30 行即可，省一个依赖。
- host 解析失败的异常（`UnknownHostException`）**不**当拦截——交给后续 TCP/HTTP 执行器按「连接失败」处理，职责清晰。

### D3. API 任务的目标解析

API 任务配置的是 URL，不是裸 host:port。`guard.check` 的调用点（`add-tcp-task-type` 的 `runApi` 已埋）需先把 URL 解析为 host/port：
- 用 `java.net.URI` 解析 host；端口缺失时按协议默认补（http→80，https→443）。
- 解析失败的 URL 不当拦截（交给 HTTP 执行器报错）。

### D4. 拦截即失败：落调用记录 + 审计

`guard.check` 抛 `BusinessException(ErrorCode.INVALID_ARGUMENT, "出站目标被拦截: host:port (命中 <cidr>)")`，被 `SchedulerEngine.run` 的既有 catch 捕获：
- 写一条失败调用记录（API：`protocol=API`；TCP：`protocol=TCP/tcpOk=false`），error 含命中网段（不含敏感信息）。
- 审计 `scheduler.task.target-blocked`（taskId/host/port/matchedCidr）。
- **不重试**，与既有执行失败语义一致。

### D5. 替换默认放行 bean

`add-tcp-task-type` 引入的 `AllowAllTaskTargetGuard`（`@Component`，空实现）与本 change 的 `CidrTaskTargetGuard` 都是 `TaskTargetGuard` 类型 → Spring 会有两个候选 bean。
- 解决：`CidrTaskTargetGuard` 标 `@Primary`，或给 `AllowAllTaskTargetGuard` 加 `@ConditionalOnMissingBean`（后者更干净——有真实实现时默认放行实现自动退场）。
- 采用 `@ConditionalOnMissingBean` 方案：`AllowAllTaskTargetGuard` 仅在没有其它 `TaskTargetGuard` bean 时装配。本 change 一旦引入 `CidrTaskTargetGuard`，默认实现自动失效，零配置切换。

### D6. 前端系统设置页新增区块

复用既有系统设置页（`AdminController` 暴露的 `/api/admin/settings` GET/PUT）：新增「出站目标防护」区块——开关 + 拦截网段列表（可增删的 tag/textarea）+ 允许网段列表。保存走既有 `SystemSettingsService.save`，热生效。

### D7. 配置校验与默认值兜底

`SystemSettingsService.normalize()` 新增：
- `taskTargetGuard` 为 null → 新建默认实例（`enabled=false` + 默认拦截网段）。
- `blockedCidrs`/`allowedCidrs` 中的非法 CIDR → 剔除并记日志（warn），不阻断保存。
- 去重、trim。

## Risks / Trade-offs

- **[DNS rebinding / TOCTOU]** host 解析为 IP 后做校验，校验通过后再发起连接，两步之间存在时间窗，理论上 DNS rebinding 可让校验时是公网 IP、连接时解析到内网 IP。**Mitigation**: 接受残余风险——探活是低频周期任务、影响有限、且本系统定位为受信运维工具；在 design/Risks 与文档明确记录。彻底防御需在连接层 pin 住解析出的 IP（用 `InetAddress` 直连而非 host），列为未来增强。
- **[多 IP host 部分拦截]** 一个 host 解析出多个 IP，部分在内网、部分公网。当前策略：任一命中 blocked 即整体拦截（保守）。**Mitigation**: 这是安全优先的合理默认；管理员可用 `allowedCidrs` 放行特定目标。
- **[配置误拦截阻断业务]** 管理员配置过严会拦掉合法任务。**Mitigation**: 默认放行；拦截落审计可追溯；`allowedCidrs` 提供逃生口。
- **[CIDR 解析性能]** 每次 check 解析 CIDR 字符串有开销。**Mitigation**: 按配置版本缓存解析后的 `Cidr` 列表（配置变更才重建）。
- **[IPv6 覆盖不全]** 本版以 IPv4 网段为主。**Mitigation**: 对 IPv6 至少拦截 `::1`（回环）与 `fc00::/7`（ULA）；精细 IPv6 策略列为未来增强。

## Migration Plan

1. **后端**：新增 `TaskTargetGuardConfig`、`CidrTaskTargetGuard`、CIDR 匹配工具；改 `SystemSettings`/`SystemSettingsService.normalize()`/`AllowAllTaskTargetGuard`（加 `@ConditionalOnMissingBean`）。TDD。
2. **前端**：系统设置页加配置区块。
3. **无 DB 迁移**：配置存既有 `la_system_settings.settings_json`。
4. **部署顺序**：必须先部署 `add-tcp-task-type`（提供 `TaskTargetGuard` 接口与调用点），再部署本 change。
5. **回滚**：还原代码即可；`enabled=false` 本身等价于回滚后的默认放行。
6. **验证**：`mvn -pl backend -am test -Dskip.frontend=true`；前端 `npm run type-check && npm run build`；手动验证：开启防护 + 配置拦截网段 → TCP/API 任务连内网 IP 被拦截并落失败记录与审计；加 allowedCidrs 后放行。

## Open Questions

- **Q1**：拦截时抛 `ErrorCode.INVALID_ARGUMENT`(400) 是否合适？语义上更像「策略禁止」。可考虑新增 `ErrorCode.TARGET_BLOCKED`(403) 更准确。倾向**新增**一个 `TARGET_BLOCKED`(403) 让语义与 HTTP 状态对齐。待确认。
- **Q2**：CIDR 匹配是否要支持「域名后缀规则」（如拦截 `*.internal.corp`）？当前判断：只做 IP 网段，域名规则增加复杂度且 DNS 解析后变 IP 已覆盖大部分场景。倾向**不做**。待确认。
