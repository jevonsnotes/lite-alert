# 09 · 部署方案（docker-compose）

## 1. 镜像构建

### 1.1 多阶段 Dockerfile

`docker/Dockerfile`：

```dockerfile
# ---------- 1) 前端构建 ----------
FROM node:20-alpine AS web
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend ./
RUN npm run build   # 输出到 /app/dist

# ---------- 2) 后端构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS api
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
# 把前端产物拷到 static
COPY --from=web /app/dist ./src/main/resources/static
RUN mvn -B -q package -DskipTests

# ---------- 3) 运行镜像 ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /opt/lite-alert
RUN addgroup -S app && adduser -S app -G app && \
    mkdir -p /data && chown -R app:app /data /opt/lite-alert
COPY --from=api /app/target/lite-alert-*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
```

## 2. docker-compose

`docker/docker-compose.yml`：

```yaml
version: "3.9"

services:
  lite-alert:
    image: lite-alert:${VERSION:-latest}
    build:
      context: ..
      dockerfile: docker/Dockerfile
    container_name: lite-alert
    restart: unless-stopped
    ports:
      - "${PORT:-8080}:8080"
    environment:
      JASYPT_ENCRYPTOR_PASSWORD: ${JASYPT_ENCRYPTOR_PASSWORD:?must be set}
      LITE_ALERT_JWT_SECRET: ${LITE_ALERT_JWT_SECRET:?must be set}
      LITE_ALERT_APIKEY_PEPPER: ${LITE_ALERT_APIKEY_PEPPER:?must be set}
      SPRING_PROFILES_ACTIVE: prod
      TZ: Asia/Shanghai
    volumes:
      - ./data:/data                            # 数据持久化
      - ./config/application-prod.yml:/opt/lite-alert/config/application-prod.yml:ro
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8080/api/admin/health"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 30s
```

`.env.example`：

```env
VERSION=latest
PORT=8080
JASYPT_ENCRYPTOR_PASSWORD=please-change-me-32-char-min
LITE_ALERT_JWT_SECRET=please-change-me-and-keep-secret
LITE_ALERT_APIKEY_PEPPER=please-change-me-and-never-rotate-without-migration
```

## 3. 配置文件挂载

`docker/config/application-prod.yml`（示例）：

```yaml
server:
  port: 8080

spring:
  config:
    import: optional:file:/opt/lite-alert/config/application-prod.yml
  mail:
    host: smtp.example.com
    port: 465
    username: notice@example.com
    password: ENC(xxxxxxxx)
    properties:
      mail.smtp.auth: true
      mail.smtp.ssl.enable: true

lite-alert:
  data-dir: /data
  bootstrap:
    admin:
      username: admin
      password: ENC(xxxxxxxx)              # Jasypt 加密的 BCrypt hash
  webhook:
    max-body-size: 65536
    rate-limit:
      per-topic-per-minute: 60
      per-contact-per-hour: 30

jasypt:
  encryptor:
    algorithm: PBEWITHHMACSHA512ANDAES_256
    iv-generator-classname: org.jasypt.iv.RandomIvGenerator
```

## 4. 启动顺序

1. 复制并填写 `.env`
2. 准备 `docker/config/application-prod.yml`
3. 准备好 SMTP / admin 密码的 Jasypt 加密值（提供小工具或脚本）
4. `docker compose up -d --build`
5. 查日志确认启动成功 → 浏览器访问 `http://host:8080` → 用 admin 登录

## 5. 升级与备份

- 升级：`git pull && docker compose build && docker compose up -d`
- 备份：直接打包 `./data` 目录（建议 cron 每日 tar 一次）
- 主密钥变更（`JASYPT_ENCRYPTOR_PASSWORD`）：必须先「迁移工具」批量重加密所有字段，再切换；不可直接换环境变量
- ApiKey pepper（`LITE_ALERT_APIKEY_PEPPER`）：**一旦设定不可变更**——pepper 改了所有现存 ApiKey 都会失效，需统一通知调用方重发；如确实要轮换，使用「批量撤销 + 引导重建」流程

## 6. 资源开销估算

- 镜像：约 220 MB（jre-alpine + 业务代码）
- 内存：稳态 256–384 MB
- CPU：单核可承载千级 QPS（webhook 接收，含 schema 校验）
