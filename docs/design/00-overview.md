# Lite-Alert 系统设计 · 总览

> 一个默认内置 H2、可切换外部数据库、前后端一体的轻量级消息通知服务。

## 1. 设计目标

| 目标 | 说明 |
| --- | --- |
| 轻量 | 单 JAR / 单容器启动，默认 H2 文件数据库，开箱即用 |
| 可生产化 | 生产环境可切换 MySQL / PostgreSQL / GaussDB / OceanBase，使用 Flyway 管理数据库结构 |
| 安全 | JWT 登录、RBAC 权限、ApiKey 只存哈希、敏感配置与字段加密、日志脱敏 |
| 一体化 | Vue 3 前端打包进 Spring Boot 静态资源，单端口对外 |
| 可扩展 | 通知渠道以策略模式抽象，支持 EMAIL / DINGTALK / FEISHU / WECOM / WEBHOOK |
| 可观测 | 健康检查、审计日志、投递记录、统计看板、traceId 贯穿 |

## 2. 技术栈

- **后端**：Spring Boot 3.5 + Java 17 + Spring Security + Spring Validation + Spring Mail + JDBC + MyBatis-Flex + Flyway + Jasypt + Caffeine + Jackson。
- **前端**：Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios + ECharts。
- **数据库**：默认 H2 文件数据库；生产支持 MySQL、PostgreSQL，GaussDB 使用 PostgreSQL 兼容模式，OceanBase 使用 MySQL 兼容模式。
- **打包**：Maven 多模块，`frontend-maven-plugin` 固定 Node/npm 版本，前端构建产物进入后端 classpath。
- **运行**：单 JAR、本地 Docker 镜像或 Docker Hub 镜像，docker-compose 可按 profile 启动外部数据库。

## 3. 模块划分

```text
lite-alert/
├── frontend/                # Vue3 + Element Plus 管理后台
├── backend/                 # Spring Boot 3 后端
│   └── src/main/java/io/litealert/
│       ├── auth/            # 登录、用户、JWT、角色、权限
│       ├── namespace/       # 命名空间
│       ├── topic/           # Topic / 报文格式 / 通道模板
│       ├── apikey/          # ApiKey 生命周期与调用方鉴权元数据
│       ├── notify/          # 通知目标、渠道、订阅、投递任务
│       ├── webhook/         # Webhook 接入、鉴权、限流、白名单
│       ├── admin/           # 系统设置、统计、审计入口
│       └── common/          # 配置、异常、加密、数据库、工具
├── docker/                  # Dockerfile、docker-compose、.env.example
└── docs/design/             # 系统设计文档
```

## 4. 文档索引

| # | 文件 | 内容 |
| --- | --- | --- |
| 01 | [架构与数据流](./01-architecture.md) | 模块边界、请求时序、Webhook 与投递链路 |
| 02 | [数据模型与存储](./02-data-model.md) | 实体定义、数据库支持、加密策略、文件辅助存储 |
| 03 | [认证与权限](./03-auth.md) | 登录、JWT、用户、角色、RBAC 权限矩阵 |
| 04 | [命名空间与 Topic](./04-namespace-topic.md) | 唯一性约束、状态机、通道模板、安全接入 |
| 05 | [报文转换](./05-message-transform.md) | JSONPath 映射、Webhook 出站模板、响应断言 |
| 06 | [通知渠道与目标](./06-notify-channel.md) | 通道抽象、通知目标、订阅、投递任务 |
| 07 | [Webhook 接入接口](./07-webhook-api.md) | 调用方鉴权、报文校验、响应契约 |
| 08 | [前端页面规划](./08-frontend.md) | 页面结构、路由、关键交互 |
| 09 | [部署方案](./09-deploy.md) | Dockerfile、docker-compose、数据库切换、配置项 |
| 10 | [实施路线图](./10-roadmap.md) | 已完成能力、后续规划与风险 |
| 11 | [ApiKey 管理](./11-apikey.md) | ApiKey、scope、有效期、轮换、限流 |

## 5. 核心概念关系图

```text
User ──owns──▶ Namespace ──contains──▶ Topic ──subscribes──▶ NotifyTarget(s)
 │                                   │                    EMAIL / DINGTALK / FEISHU / WECOM / WEBHOOK
 │                                   ├── inboundFormat(JSON Schema)
 │                                   ├── auth.mode / keyLocation / rateLimit / ipWhitelist
 │                                   └── templates[NotifyTarget.Type]
 │                                           ├── subject/body
 │                                           ├── transform(JSONPath mappings)
 │                                           ├── outputTemplate / outputXmlTemplate
 │                                           └── responseCheck(WEBHOOK)
 │
 └─owns──▶ ApiKey ──scopes──▶ Namespace[*] / Topic[*]
              ├── validFrom / validUntil / status
              ├── prefix / keyHash(不存原文)
              └── rotateCount / usageCount / rateLimitPerMinute

Caller ──HTTP POST + ApiKey──▶ /api/webhook/{namespace}/{topic}
         └─▶ ApiKey 校验 + scope 校验 + Schema 校验 + 转换/模板
             └─▶ NotifyDelivery 持久化任务 ──▶ NotifyChannel 异步投递
```

## 6. 非功能性约束

- 数据持久化以数据库为准，默认 H2 文件数据库；复杂业务字段以 JSON 文本存储，兼容多数据库。
- 文件存储能力仍保留在 `common.storage.FileStore`，用于兼容辅助文件、导入导出或未来迁移场景，不再作为核心业务数据的唯一持久化方案。
- 单实例部署为主，不引入外部 MQ；投递任务通过数据库持久化与后台 worker 处理。
- 日志默认不打印原始报文、ApiKey 原文、通知目标密钥等敏感信息。
- ApiKey 原文只允许创建或轮换后一次性返回，服务端不得存储或再次展示。
