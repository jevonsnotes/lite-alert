# 09 · 部署方案（docker-compose）

## 1. 镜像构建

当前 Dockerfile 使用两阶段构建：

1. **构建阶段**：`maven:3.9-eclipse-temurin-17`。
   - Maven 先下载后端依赖。
   - `frontend-maven-plugin` 在 Maven 构建中下载固定版本 Node/npm。
   - 前端执行安装与构建，产物进入后端 classpath。
   - 生成 `backend/target/lite-alert.jar`。
2. **运行阶段**：`eclipse-temurin:17-jre-alpine`。
   - 创建非 root 用户 `app`。
   - 准备 `/data` 与 `/opt/lite-alert/config`。
   - 安装 `wget` 用于容器健康检查。

关键片段：

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS api
WORKDIR /workspace
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl backend -am dependency:go-offline
COPY backend/src backend/src
COPY frontend frontend
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/frontend/node_modules \
    mvn -B -q -pl backend -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine
COPY --from=api /workspace/backend/target/lite-alert.jar app.jar
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
```

## 2. docker-compose 服务

`docker/docker-compose.yml` 包含：

| 服务 | 默认启动 | 说明 |
| --- | --- | --- |
| `lite-alert` | 是 | 主应用，默认使用 H2 文件数据库 |
| `mysql` | 否 | `COMPOSE_PROFILES=mysql` 时启动 |
| `postgres` | 否 | `COMPOSE_PROFILES=postgres` 时启动 |

主应用镜像：

```yaml
image: ${IMAGE:-jevonsnotes/lite-alert:${VERSION:-latest}}
```

- 默认可从 Docker Hub 拉取 `jevonsnotes/lite-alert:${VERSION}`。
- 如需本地构建，可取消 compose 中 `build` 块注释，并按需要调整 `image`。

健康检查：

```yaml
healthcheck:
  test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8080/api/health"]
```

## 3. 环境变量

### 3.1 必填密钥

| 变量 | 说明 |
| --- | --- |
| `JASYPT_ENCRYPTOR_PASSWORD` | Jasypt 主密钥，用于解密 `ENC(...)` 和敏感字段 |
| `LITE_ALERT_JWT_SECRET` | JWT HS256 签名密钥，生产建议至少 32 字符 |
| `LITE_ALERT_APIKEY_PEPPER` | ApiKey HMAC pepper；一旦生产使用，不应直接更换 |

### 3.2 通用变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VERSION` | `latest` | 镜像标签 |
| `IMAGE` | 空 | 指定后直接拉取该镜像 |
| `PORT` | `8080` | 宿主机暴露端口 |
| `SPRING_PROFILES_ACTIVE` | `prod` | compose 中固定为 prod |
| `TZ` | `Asia/Shanghai` | 容器时区 |

### 3.3 数据库变量

| 变量 | 说明 |
| --- | --- |
| `LITE_ALERT_DATASOURCE_URL` | JDBC URL |
| `LITE_ALERT_DATASOURCE_USERNAME` | 数据库用户名 |
| `LITE_ALERT_DATASOURCE_PASSWORD` | 数据库密码 |
| `LITE_ALERT_DATASOURCE_DRIVER` | JDBC Driver 类名 |
| `LITE_ALERT_DATABASE_TYPE` | `h2` / `mysql` / `postgresql` / `gaussdb` / `oceanbase`，决定 Flyway 迁移目录 |
| `COMPOSE_PROFILES` | `mysql` 或 `postgres` 时自动启动对应数据库容器 |

## 4. 启动方式

### 4.1 默认 H2

```bash
cd docker
cp .env.example .env
# 修改三个密钥
docker compose up -d
```

H2 数据文件默认挂载到 `docker/data`。

### 4.2 MySQL

`.env` 中启用：

```env
COMPOSE_PROFILES=mysql
LITE_ALERT_DATASOURCE_URL=jdbc:mysql://mysql:3306/lite_alert?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true
LITE_ALERT_DATASOURCE_USERNAME=root
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver
LITE_ALERT_DATABASE_TYPE=mysql
MYSQL_ROOT_PASSWORD=litealert
```

启动：

```bash
docker compose up -d
```

### 4.3 PostgreSQL

`.env` 中启用：

```env
COMPOSE_PROFILES=postgres
LITE_ALERT_DATASOURCE_URL=jdbc:postgresql://postgres:5432/lite_alert
LITE_ALERT_DATASOURCE_USERNAME=postgres
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=org.postgresql.Driver
LITE_ALERT_DATABASE_TYPE=postgresql
POSTGRES_PASSWORD=litealert
```

### 4.4 GaussDB / OceanBase

这两类数据库通常使用外部托管实例，不由 compose 内置启动：

- GaussDB：使用 PostgreSQL JDBC Driver，`LITE_ALERT_DATABASE_TYPE=gaussdb`。
- OceanBase：使用 MySQL JDBC Driver，`LITE_ALERT_DATABASE_TYPE=oceanbase`。

具体配置参考 `docker/.env.example`。

## 5. 配置挂载

主应用支持挂载额外配置目录：

```yaml
volumes:
  - ./data:/data
  # - ./config:/opt/lite-alert/config:ro
```

可在 `./config/application-prod.yml` 中覆盖 Spring Boot 配置。敏感值建议继续使用环境变量或 `ENC(...)`。

## 6. 升级与备份

| 场景 | 建议 |
| --- | --- |
| H2 备份 | 停止容器或确保无写入后备份 `docker/data` |
| MySQL/PostgreSQL 备份 | 使用数据库原生 dump / 备份策略 |
| 应用升级 | 拉取新镜像后 `docker compose up -d`，Flyway 自动执行结构迁移 |
| Jasypt 主密钥轮换 | 需要先批量重加密敏感字段，再切换环境变量 |
| ApiKey pepper 轮换 | 会导致现存 ApiKey 全部失效，需通过统一撤销/重建或迁移流程处理 |

## 7. 资源开销估算

- 内存：小规模使用建议 512 MB 以上。
- CPU：单核可支撑轻量 Webhook 接入与后台投递，具体吞吐取决于 Schema 复杂度与下游通道耗时。
- 数据库：H2 适合开箱即用；生产高并发或更严格备份恢复要求建议使用 MySQL / PostgreSQL。
