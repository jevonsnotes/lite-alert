# Lite-Alert

> 一个无数据库化、文件加密存储、前后端一体的轻量级消息通知服务。
> 单 JAR 启动，配置即可，docker-compose 一键部署。

## 特性

- **轻量**：单 JAR、无外部数据库 / 消息队列依赖
- **安全**：数据落盘加密，敏感配置 Jasypt 包裹
- **一体化**：Vue 3 前端打包进 Spring Boot 静态资源，单端口对外
- **Webhook 接入**：HTTP 调用 → Schema 校验 → 报文转换（JSONPath） → 邮件通知
- **ApiKey**：独立凭证，支持有效期、scope 授权、撤销
- **公开 Topic**：可配置免认证 + IP 白名单，适配 GitHub / GitLab 等回调
- **可扩展**：通知渠道以策略模式抽象，邮件之外可逐步加钉钉 / 飞书 / SMS

## 文档

完整设计放在 [`docs/design`](./docs/design)：

| # | 内容 |
| --- | --- |
| 00 | [总览](./docs/design/00-overview.md) |
| 01 | [架构与数据流](./docs/design/01-architecture.md) |
| 02 | [数据模型与文件存储](./docs/design/02-data-model.md) |
| 03 | [认证与权限](./docs/design/03-auth.md) |
| 04 | [命名空间与 Topic](./docs/design/04-namespace-topic.md) |
| 05 | [报文转换](./docs/design/05-message-transform.md) |
| 06 | [通知渠道与邮箱](./docs/design/06-notify-channel.md) |
| 07 | [Webhook 接入接口](./docs/design/07-webhook-api.md) |
| 08 | [前端页面规划](./docs/design/08-frontend.md) |
| 09 | [部署方案](./docs/design/09-deploy.md) |
| 10 | [实施路线图](./docs/design/10-roadmap.md) |
| 11 | [ApiKey 管理](./docs/design/11-apikey.md) |

## 工程结构

```
lite-alert/
├── pom.xml                      # Maven 父 pom (Spring Boot 3.5.15, Java 17)
├── backend/                     # Spring Boot 单模块
│   └── src/main/...
├── frontend/                    # Vite + Vue 3 + Element Plus
│   └── src/...
├── docker/
│   ├── Dockerfile               # 多阶段：node 构建 → maven 构建 → jre 运行
│   ├── docker-compose.yml
│   └── .env.example
└── docs/design/                 # 系统设计文档
```

## 快速开始

### 本地开发（前后端分离）

需要 JDK 17 + Maven 3.9+ + Node.js 20+。

```bash
# 1) 启后端（端口 8080）
mvn -pl backend -am spring-boot:run

# 2) 另开一个终端启前端（端口 5173，已配置 /api 反代到 8080）
cd frontend
npm install
npm run dev
```

浏览器访问 http://localhost:5173 ，请求会代理到后端 8080。

### 一体打包（前端嵌入 JAR）

```bash
cd frontend && npm install && npm run build   # 输出到 backend/src/main/resources/static
cd ..
mvn -pl backend -am package -DskipTests
java -jar backend/target/lite-alert.jar
```

访问 http://localhost:8080 即是完整应用。

### Docker Compose 部署

#### 1. 准备环境

```bash
cd docker
cp .env.example .env
```

编辑 `.env`，至少填写三个必填密钥：

```ini
JASYPT_ENCRYPTOR_PASSWORD=<your-32-char-min-secret>
LITE_ALERT_JWT_SECRET=<your-32-char-min-secret>
LITE_ALERT_APIKEY_PEPPER=<your-32-char-min-secret>
```

#### 2. 选择数据库模式

数据库切换通过 `.env` 中的 `COMPOSE_PROFILES` 变量控制，无需额外命令行参数。

| 模式 | 配置 | 说明 |
|------|------|------|
| **H2**（默认） | 无需修改 | 开箱即用，数据在 `./data` |
| **MySQL** | `COMPOSE_PROFILES=mysql` + 取消注释 MySQL 配置段 | 自动拉起 MySQL 8.0 容器 |
| **PostgreSQL** | `COMPOSE_PROFILES=postgres` + 取消注释 PostgreSQL 配置段 | 自动拉起 PostgreSQL 16 容器 |
| **外部数据库** | 留空 `COMPOSE_PROFILES` + 配置外部 JDBC URL | 适用于 GaussDB / OceanBase / 自建库 |

MySQL 示例（`.env`）：

```ini
COMPOSE_PROFILES=mysql
LITE_ALERT_DATASOURCE_URL=jdbc:mysql://mysql:3306/lite_alert?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true
LITE_ALERT_DATASOURCE_USERNAME=root
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver
LITE_ALERT_DATABASE_TYPE=mysql
MYSQL_ROOT_PASSWORD=litealert
```

#### 3. 启动

```bash
docker compose up -d
```

Compose 会根据 `COMPOSE_PROFILES` 自动决定是否拉起 MySQL/PostgreSQL 容器。

#### 4. 验证与访问

```bash
docker compose ps
docker compose logs -f lite-alert
curl http://localhost:8080/api/health
```

浏览器打开 `http://localhost:8080`，初始管理员账号 `admin` / `admin123`。

#### 5. 版本升级

使用 Docker Hub 镜像升级，在 `.env` 中指定版本：

```ini
IMAGE=jevonsnotes/lite-alert:1.0.0
```

```bash
docker compose down
docker compose up -d
```

## 默认账号

`application.yml` 内置初始管理员（仅当 `users.json` 不存在时生效）：

| 用户名 | 密码 |
| --- | --- |
| `admin` | `admin123`（dev profile 默认；生产部署请通过 ENC(...) 覆盖） |

> 首次登录后请及时修改管理员密码。

## 配置项

主要环境变量：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `JASYPT_ENCRYPTOR_PASSWORD` | 生产必填 | 解密 application.yml 中的 ENC(…) |
| `LITE_ALERT_JWT_SECRET` | 生产必填 | JWT 签名密钥（≥ 32 字符） |
| `LITE_ALERT_APIKEY_PEPPER` | 生产必填 | ApiKey HMAC pepper，**一旦设定不可轮换** |
| `LITE_ALERT_DATA_DIR` | 否 | 数据目录，默认 `./data`（容器内 `/data`） |
| `SPRING_PROFILES_ACTIVE` | 否 | 默认 `dev`，生产建议 `prod` |

## 许可

[MIT](./LICENSE)
