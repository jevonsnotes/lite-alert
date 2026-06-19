# 10 · 实施路线图

> 本文按项目当前状态更新：M0–M7 主线能力已基本落地，当前重点从“搭建可用闭环”转向“生产化、可观测、易运维和通道增强”。

## 当前已具备能力

| 领域 | 当前状态 |
| --- | --- |
| 工程结构 | Maven 多模块，后端单模块打包，前端由 Maven 构建并进入后端静态资源 |
| 前端后台 | Vue 3 + Element Plus，已有 Dashboard、Namespace、Topic、ApiKey、Contacts、Audit、Users、Roles、System 页面 |
| 认证权限 | JWT 登录、用户管理、角色管理、权限常量与路由权限控制 |
| 数据库 | 默认 H2，支持 MySQL、PostgreSQL、GaussDB、OceanBase；Flyway 分数据库迁移 |
| Namespace / Topic | 命名空间与 Topic CRUD、发布/禁用/启用、Topic 安全配置与通道模板 |
| ApiKey | 独立 ApiKey、scope、有效期、撤销、轮换、覆盖查询、使用统计、限流字段 |
| Webhook 接入 | Header / Query 两种 keyLocation、ApiKey 鉴权、Schema 校验、限流、IP 白名单 |
| 通知通道 | EMAIL、DINGTALK、FEISHU、WECOM、WEBHOOK |
| 模板与转换 | Mustache、JSONPath mapping、Webhook JSON/XML 出站模板、响应断言 |
| 投递管理 | NotifyDelivery 持久化、worker、查询接口、历史清理 |
| 运维部署 | Dockerfile、Docker Compose、Docker Hub 镜像名、健康检查、外部数据库 profile |

## M0 · 工程脚手架（已完成）

- 仓库结构 / Maven / Vite 工程。
- Dockerfile 多阶段构建。
- 前端构建集成到 Maven。

## M1 · 认证与后台骨架（已完成）

- 登录 / JWT / 用户 CRUD。
- 前端 Login、Layout、路由守卫。
- `GET /api/auth/me` 返回当前用户信息。

## M2 · 存储基座升级（已完成）

- 从纯文件存储升级为数据库持久化。
- 默认 H2，生产可切换 MySQL / PostgreSQL / GaussDB / OceanBase。
- Flyway 管理结构迁移。
- 保留 FileStore 作为辅助能力。

## M3 · 命名空间 + Topic（已完成）

- 命名空间 CRUD、启用/禁用。
- Topic CRUD、发布/禁用/启用。
- JSON Schema、Topic auth、keyLocation、templates 字段。

## M4 · ApiKey 与 Webhook 鉴权（已完成）

- ApiKey HMAC 哈希存储，不保存原文。
- Topic / Namespace scope。
- 撤销、删除、轮换、覆盖查询。
- Header / Query 两种传 key 方式。
- Webhook 统一接入 ApiKey 鉴权主线。

## M5 · 报文转换与模板（已完成）

- JSONPath mapping。
- Mustache 模板。
- 通道专属模板。
- Webhook JSON/XML 出站模板。
- Webhook 响应断言。

## M6 · 通知通道与投递管理（已完成）

- EMAIL、DINGTALK、FEISHU、WECOM、WEBHOOK 通道。
- Topic 订阅 NotifyTarget。
- NotifyDelivery 持久化任务、后台 worker、查询接口、清理任务。

## M7 · 管理与运维能力（已完成）

- Dashboard 统计。
- 审计查询。
- 用户与角色管理。
- 系统设置与 SMTP 配置。
- Docker Compose 多数据库部署。
- `/api/health` 与 `/api/admin/health`。

## M8 · 生产化打磨（进行中 / 后续）

建议按优先级推进：

1. **部署文档与示例完善**：补充不同数据库的完整生产配置、备份恢复步骤、升级注意事项。
2. **测试覆盖增强**：补足 Webhook、ApiKey、投递 worker、角色权限、数据库迁移的集成测试。
3. **前端体验优化**：复杂表单拆分、模板编辑器增强、错误提示统一、移动端/窄屏适配。
4. **投递可观测增强**：按 traceId 串联调用、投递、重试、下游响应断言结果。
5. **安全加固**：敏感 payload 查看审计、ApiKey 轮换提醒、永久 key 风险提示。

## M9 · 可选增强

| 方向 | 说明 |
| --- | --- |
| 多语言 | i18n 骨架与中英文切换 |
| 更多通道 | Slack、Telegram、短信、语音电话等 |
| 导入导出 | Namespace / Topic / 模板 / ApiKey scope 的配置迁移 |
| 高可用 | 外部队列、分布式锁、多实例投递 worker 协调 |
| 监控集成 | Prometheus 指标、Grafana dashboard、OpenTelemetry trace |

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 主密钥泄露 | 主密钥只通过环境变量提供；提供重加密迁移流程 |
| ApiKey pepper 误轮换 | 文档和系统设置中强提示；轮换前批量撤销/重建或迁移 |
| URL 参数泄露 key | 默认推荐 Header；Query 模式增加风险提示 |
| 下游通道不稳定 | NotifyDelivery 持久化、重试、失败记录、响应断言 |
| 数据库迁移失败 | Flyway 迁移脚本按数据库类型维护，升级前备份 |
| 报文过大或恶意调用 | body 大小限制、Topic / IP / ApiKey 限流、IP 白名单 |
| 敏感 payload 泄露 | 默认脱敏，查看原文需 `DELIVERY_PAYLOAD_READ`，并写审计 |
