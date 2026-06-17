# Lite-Alert 系统设计 · 总览

> 一个无数据库化、文件加密存储、前后端一体的轻量级消息通知服务。

## 1. 设计目标

| 目标 | 说明 |
| --- | --- |
| 轻量 | 单 JAR 启动、无外部 DB / MQ 依赖 |
| 安全 | 数据落盘加密，配置敏感项 Jasypt 加密 |
| 一体化 | Vue3 前端打包进 Spring Boot 静态资源，单端口对外 |
| 可扩展 | 通知渠道(邮件/钉钉/飞书/短信)以策略模式抽象，预留扩展点 |
| 易部署 | 单 docker-compose 文件，一条命令起服务 |

## 2. 技术栈

- **后端**：Spring Boot 3.x + Java 17 + Spring Mail + Jasypt + Caffeine（内存索引）
- **前端**：Vue 3 + Vite + Element Plus + Pinia + Vue Router
- **打包**：Maven 多模块，前端构建产物拷贝到 `backend/src/main/resources/static`
- **运行**：单 JAR + 数据卷挂载，docker-compose 启动

## 3. 模块划分

```
lite-alert/
├── frontend/                # Vue3 + Element Plus
├── backend/                 # Spring Boot 3
│   ├── auth/                # 登录、用户、JWT
│   ├── namespace/           # 命名空间
│   ├── topic/               # Topic / 报文格式 / 转换规则 / Webhook
│   ├── notify/              # 通知渠道(邮件 / 钉钉 / 飞书 / 企业微信)、通知目标
│   ├── storage/             # 文件存储 + 加密 + 索引
│   ├── transform/           # JSONPath 转换引擎
│   └── common/              # 配置、异常、过滤器
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml
└── docs/design/             # 本设计文档
```

## 4. 文档索引

| # | 文件 | 内容 |
| --- | --- | --- |
| 01 | [架构与数据流](./01-architecture.md) | 模块边界、请求时序、Webhook 链路 |
| 02 | [数据模型与文件存储](./02-data-model.md) | 实体定义、文件布局、Jasypt 加密策略 |
| 03 | [认证与权限](./03-auth.md) | 管理员/普通用户、密码加密、JWT、RBAC |
| 04 | [命名空间与 Topic](./04-namespace-topic.md) | 唯一性约束、状态机、报文格式定义 |
| 05 | [报文转换](./05-message-transform.md) | JSONPath 字段映射表的语义与执行 |
| 06 | [通知渠道与目标](./06-notify-channel.md) | 渠道抽象、通知目标、Topic 关联订阅 |
| 07 | [Webhook 接入接口](./07-webhook-api.md) | Webhook 鉴权、报文校验、调用契约 |
| 08 | [前端页面规划](./08-frontend.md) | 页面结构、路由、关键交互 |
| 09 | [部署方案](./09-deploy.md) | Dockerfile、docker-compose、配置项 |
| 10 | [实施路线图](./10-roadmap.md) | 里程碑与开发顺序建议 |
| 11 | [ApiKey 管理](./11-apikey.md) | 独立的 ApiKey 模块、scope 授权、有效期、调用鉴权流程 |

## 5. 核心概念关系图

```
User ──owns──▶ Namespace ──contains──▶ Topic ──subscribes──▶ NotifyTarget(s)
     │                                    │                    (EMAIL / DINGTALK / FEISHU / WECOM)
     │                                    ├── inboundFormat (JSON Schema)
     │                                    ├── transformRule (JSONPath 映射，可选)
     │                                    └── auth.mode  (API_KEY / NONE)
     │
     └─owns──▶ ApiKey ──scopes──▶ Namespace[*] / Topic[*]
                  │
                  └── validFrom / validUntil(可永久) / status

调用方 ──HTTP POST  Authorization: Bearer <apiKey>──▶ /api/webhook/{ns}/{topic}
                       ──ApiKey 校验 + scope 校验 + 报文校验/转换──▶
                       NotifyChannel ──▶ 订阅的通知目标
```

## 6. 非功能性约束

- 单实例运行，不考虑分布式一致性（如未来要扩展，预留 Redis/外部存储抽象口）
- 数据文件以 namespace 维度切分，避免单文件过大
- 所有写入采用 "临时文件 + 原子 rename" 防止崩溃半写
- 日志默认不打印任何报文 body，敏感字段全程脱敏
