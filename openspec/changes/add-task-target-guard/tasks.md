## 1. 配置模型与系统设置集成

- [x] 1.1 新建 `TaskTargetGuardConfig`（`SystemSettings` 内部静态类）：`enabled`(boolean,默认 false)、`blockedCidrs`(List<String>,默认内置网段)、`allowedCidrs`(List<String>)；提供 `defaultBlockedCidrs()` 常量（10/8、172.16/12、192.168/16、127/8、169.254/16、0/8）
- [x] 1.2 `SystemSettings` 新增字段 `taskTargetGuard = new TaskTargetGuardConfig()`
- [x] 1.3 `SystemSettingsService.normalize()` 补 `taskTargetGuard` null 兜底；对 `blockedCidrs`/`allowedCidrs` 逐条做 CIDR 合法性校验，剔除非法项并 warn 日志，去重 trim
- [x] 1.4 `SystemSettingsService.save()` 审计补 `taskTargetGuard`（enabled + 网段计数，不记明细敏感信息）
- [x] 1.5 写测试：默认配置 enabled=false 且含内置网段；保存含非法 CIDR 的配置后被剔除且不阻断；allowedCidrs 优先级

## 2. CIDR 匹配工具

- [x] 2.1 新建 CIDR 匹配工具类（IPv4 用 `BigInteger` 掩码包含判断；IPv6 至少支持 `::1`、`fc00::/7`）：解析 `cidr` 字符串为不可变 `Cidr`、`boolean contains(InetAddress)`
- [x] 2.2 写工具测试：典型私有/回环/链路本地网段命中；边界 IP（网络号/广播地址）；非法 CIDR 解析返回空/异常；IPv6 回环命中

## 3. 真实 TaskTargetGuard 实现

- [x] 3.1 新建 `CidrTaskTargetGuard implements TaskTargetGuard`（`@Component`）：注入 `SystemSettingsService`；`check(host,port)`——enabled=false 直接 return；`InetAddress.getAllByName(host)` 解析全部 IP，任一命中 allowed 跳过，命中 blocked 抛 `BusinessException`
- [x] 3.2 CIDR 解析结果按配置内容缓存（配置字符串指纹变更才重建 `List<Cidr>`），避免每次 check 重复解析
- [x] 3.3 `AllowAllTaskTargetGuard`（来自 add-tcp-task-type）加 `@ConditionalOnMissingBean(TaskTargetGuard.class)`，使真实实现零配置接管（D5）
- [x] 3.4 写测试：默认放行（enabled=false）；enabled=true 拦截内网 IP；allowedCidrs 覆盖放行；多 IP host 任一命中即拦截；`UnknownHostException` 不当拦截（交执行器）

## 4. 拦截落库与审计

- [x] 4.1 确认 `SchedulerEngine.run` 的既有 catch 能承接 `guard.check` 抛出的 `BusinessException`：TCP 路径落 `protocol=TCP/tcpOk=false/失败` + error；API 路径落 `protocol=API/失败` + error（依赖 add-tcp-task-type 已埋的调用点与 catch）
- [x] 4.2 拦截时审计 `scheduler.task.target-blocked`（taskId/host/port/matchedCidr）；error_message 含命中网段但不含敏感信息
- [x] 4.3 写集成测试：开启防护 + 内网目标 → 生成失败调用记录（含命中网段）+ target-blocked 审计；加 allowedCidrs 后放行并成功

## 5. ErrorCode（如确认新增）

- [x] 5.1 若 Open Question Q1 确认新增：`ErrorCode` 加 `TARGET_BLOCKED(403,"outbound target blocked by guard")`；`CidrTaskTargetGuard` 用该码；否则沿用 `INVALID_ARGUMENT`（待 Q1 确认后执行）

## 6. 前端系统设置页

- [x] 6.1 系统设置页新增「出站目标防护」区块：开关 + 拦截网段列表（可增删的 tag/textarea）+ 允许网段列表
- [x] 6.2 加载/保存走既有 `/api/admin/settings` GET/PUT，字段 `taskTargetGuard`；保存后提示热生效
- [x] 6.3 `npm run type-check && npm run build` 转绿

## 7. 文档与收尾

- [x] 7.1 更新 `docs/design/12-scheduled-tasks.md` 安全一节：出站目标防护已实现（默认放行、可配置、热生效），记录 DNS rebinding/TOCTOU 残余风险与 IPv6 覆盖范围
- [x] 7.2 后端全量测试：`mvn -pl backend -am test -Dskip.frontend=true` 转绿
- [x] 7.3 一体打包验证：`mvn -pl backend -am package` 通过
- [x] 7.4 输出中文变更摘要、测试结果、未完成风险（DNS rebinding 残余风险、IPv6 精细策略待增强）
- [x] 7.5 确认依赖关系：本 change 必须在 `add-tcp-task-type` 部署后才生效；归档时记录依赖
