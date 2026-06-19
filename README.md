# Lite-Alert

Lite-Alert 是一个前后端一体的轻量级消息通知服务。它默认使用内置 H2 文件数据库，单 JAR 即可启动；生产环境可切换 MySQL、PostgreSQL，以及兼容协议的 GaussDB、OceanBase。

核心目标是让业务系统用一个简单的 Webhook 调用，把结构化事件转成邮件、钉钉、飞书、企业微信或通用出站 Webhook 通知，并在后台完成鉴权、校验、转换、投递和审计追踪。

<img width="1920" height="879" alt="image" src="https://github.com/user-attachments/assets/e4bc0ccf-95e0-4eb8-8bba-ed78c097b69c" />
<img width="1920" height="879" alt="image" src="https://github.com/user-attachments/assets/ac5275f7-a721-450e-b0cc-10290ce441fa" />
<img width="1920" height="879" alt="image" src="https://github.com/user-attachments/assets/190dffa0-0c80-4857-9b9c-4d897be7f003" />



## 功能特性

- **单体交付**：Spring Boot 后端与 Vue 3 前端打包进同一个 JAR / Docker 镜像。
- **多数据库支持**：默认 H2 文件数据库；生产可通过环境变量切换 MySQL / PostgreSQL / GaussDB / OceanBase。
- **Webhook 接入**：按 `Namespace + Topic` 暴露接入地址，支持 JSON Schema 校验、限流、IP 白名单和 traceId 追踪。
- **ApiKey 鉴权**：ApiKey 独立管理，支持 Topic / Namespace 授权范围、生效失效时间、撤销、轮换和调用统计；原文只在创建/轮换时展示一次。
- **消息转换与模板**：支持 JSONPath 字段映射、Mustache 模板变量、通道专属模板，以及 Webhook 出站 JSON/XML 模板与响应断言。
- **多通知通道**：支持 EMAIL、DINGTALK、FEISHU、WECOM、WEBHOOK。
- **投递管理**：投递任务持久化，支持异步派发、重试、失败记录、投递详情查询和敏感 payload 权限控制。
- **管理后台**：命名空间、Topic、ApiKey、通知目标、审计、用户、角色、系统设置等页面。
- **安全配置**：JWT 登录、RBAC 权限、Jasypt 配置加密、敏感字段脱敏与审计日志。

## 技术栈

- **后端**：Java 17、Spring Boot 3.5、Spring Security、Spring Validation、Spring Mail、JDBC、MyBatis-Flex、Flyway、Jasypt、Caffeine、Jackson。
- **数据库**：H2（默认）、MySQL、PostgreSQL；GaussDB 使用 PostgreSQL 协议，OceanBase 使用 MySQL 协议。
- **前端**：Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、Axios、ECharts。
- **打包**：Maven 多模块，`frontend-maven-plugin` 固定 Node/npm 版本并构建前端资源。
- **部署**：单 JAR 或 Docker Compose。

## 快速启动

### 方式一：Docker Compose（推荐）

项目已提供 Docker Compose 样例文件：

- Compose 文件：`docker/docker-compose.yml`
- 环境变量样例：`docker/.env.example`

从项目根目录启动：

```bash
cp docker/.env.example docker/.env
# 编辑 docker/.env，至少修改 JASYPT_ENCRYPTOR_PASSWORD / LITE_ALERT_JWT_SECRET / LITE_ALERT_APIKEY_PEPPER
docker compose -f docker/docker-compose.yml --env-file docker/.env up -d
```

也可以进入 `docker` 目录启动：

```bash
cd docker
cp .env.example .env
# 编辑 .env，至少修改 JASYPT_ENCRYPTOR_PASSWORD / LITE_ALERT_JWT_SECRET / LITE_ALERT_APIKEY_PEPPER
docker compose up -d
```

默认使用 H2 文件数据库，数据目录位于 `docker/data`。启动后访问：

```text
http://localhost:8080
```

健康检查：

```text
GET http://localhost:8080/api/health
```

### 切换 MySQL

在 `docker/.env` 中启用 MySQL 配置：

```env
COMPOSE_PROFILES=mysql
LITE_ALERT_DATASOURCE_URL=jdbc:mysql://mysql:3306/lite_alert?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true
LITE_ALERT_DATASOURCE_USERNAME=root
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver
LITE_ALERT_DATABASE_TYPE=mysql
MYSQL_ROOT_PASSWORD=litealert
```

然后执行：

```bash
docker compose up -d
```

### 切换 PostgreSQL

在 `docker/.env` 中启用 PostgreSQL 配置：

```env
COMPOSE_PROFILES=postgres
LITE_ALERT_DATASOURCE_URL=jdbc:postgresql://postgres:5432/lite_alert
LITE_ALERT_DATASOURCE_USERNAME=postgres
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=org.postgresql.Driver
LITE_ALERT_DATABASE_TYPE=postgresql
POSTGRES_PASSWORD=litealert
```

GaussDB / OceanBase 可参考 `docker/.env.example`，分别使用 PostgreSQL / MySQL 兼容 JDBC 驱动与迁移脚本。

## 本地开发

### 后端测试

```bash
mvn -pl backend -am test -Dskip.frontend=true
```

### 前端类型检查与构建

```bash
cd frontend
npm run type-check
npm run build
```

### 一体打包

```bash
mvn -pl backend -am package
```

不跳过前端时，Maven 会下载固定版本的 Node/npm，执行前端安装与构建，并把产物复制到后端静态资源目录。

## Webhook 调用示例

默认通过请求头传递 ApiKey：

```bash
curl -X POST "http://localhost:8080/api/webhook/demo/order_paid" \
  -H "Authorization: Bearer la_xxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","amount":99.5}'
```

如果 Topic 配置为 `keyLocation=QUERY`，也可通过 URL 参数传递：

```bash
curl -X POST "http://localhost:8080/api/webhook/demo/order_paid?key=la_xxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","amount":99.5}'
```

成功响应为 `200 OK`，表示请求已受理、投递任务已创建；实际投递结果可在后台审计或投递记录中查看。

## 主要目录

```text
lite-alert/
├── backend/                 # Spring Boot 后端
│   └── src/main/java/io/litealert/
│       ├── auth/            # 登录、用户、JWT、角色与权限
│       ├── namespace/       # 命名空间
│       ├── topic/           # Topic、报文格式、通道模板
│       ├── apikey/          # ApiKey 生命周期与鉴权元数据
│       ├── notify/          # 通知目标、通知渠道、订阅、投递
│       ├── webhook/         # Webhook 接入、鉴权、限流、白名单
│       ├── admin/           # 系统设置、统计、审计
│       └── common/          # 通用配置、错误、加密、数据库、工具
├── frontend/                # Vue 3 前端
├── docker/                  # Dockerfile、docker-compose、环境变量示例
└── docs/design/             # 系统设计文档
```

## 设计文档

- [系统设计总览](docs/design/00-overview.md)
- [架构与数据流](docs/design/01-architecture.md)
- [数据模型与存储](docs/design/02-data-model.md)
- [认证与权限](docs/design/03-auth.md)
- [命名空间与 Topic](docs/design/04-namespace-topic.md)
- [报文转换](docs/design/05-message-transform.md)
- [通知渠道与目标](docs/design/06-notify-channel.md)
- [Webhook 接入接口](docs/design/07-webhook-api.md)
- [前端页面规划](docs/design/08-frontend.md)
- [部署方案](docs/design/09-deploy.md)
- [实施路线图](docs/design/10-roadmap.md)
- [ApiKey 管理](docs/design/11-apikey.md)

## 安全注意事项

- 生产环境必须替换 `.env` 中的三个密钥：`JASYPT_ENCRYPTOR_PASSWORD`、`LITE_ALERT_JWT_SECRET`、`LITE_ALERT_APIKEY_PEPPER`。
- `LITE_ALERT_APIKEY_PEPPER` 变更会导致所有现存 ApiKey 失效，除非执行专门迁移或统一轮换。
- ApiKey 原文只在创建或轮换后一次性展示，服务端不会存储，也无法再次找回。
- URL 参数传 key 更容易进入代理日志或浏览器历史，优先使用 `Authorization` 请求头。
- 不要在日志中打印 Webhook 原始报文和通知目标密钥。
