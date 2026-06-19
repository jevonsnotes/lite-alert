# Lite-Alert

Lite-Alert is a lightweight, all-in-one message notification service with a Spring Boot backend and a Vue 3 admin console. It starts out of the box with an embedded H2 file database and can be switched to MySQL, PostgreSQL, GaussDB, or OceanBase for production deployments.

It lets business systems send structured events to a simple Webhook endpoint, then handles authentication, JSON Schema validation, transformation, delivery, retry, and audit tracking.

## Features

- **All-in-one delivery**: Vue 3 frontend and Spring Boot backend are packaged into one JAR / Docker image.
- **Multiple databases**: H2 by default; MySQL, PostgreSQL, GaussDB, and OceanBase for production.
- **Webhook ingestion**: Exposes endpoints by `Namespace + Topic`, with schema validation, rate limiting, IP allowlists, and trace IDs.
- **ApiKey authentication**: Topic / Namespace scopes, validity windows, revoke, rotate, usage stats, and one-time key reveal.
- **Message transformation**: JSONPath mappings, Mustache variables, channel-specific templates, outbound Webhook JSON/XML templates, and response assertions.
- **Delivery channels**: EMAIL, DINGTALK, FEISHU, WECOM, and generic WEBHOOK.
- **Delivery management**: Persistent delivery jobs, async workers, retry, failure records, delivery detail APIs, and sensitive payload permission control.
- **Admin console**: Namespaces, Topics, ApiKeys, notification targets, audit logs, users, roles, and system settings.
- **Security**: JWT login, RBAC permissions, Jasypt encryption, sensitive field masking, and audit logging.

## Tech Stack

| Layer | Technologies |
| --- | --- |
| Backend | Java 17, Spring Boot 3.5, Spring Security, Spring Validation, Spring Mail, JDBC, MyBatis-Flex, Flyway, Jasypt, Caffeine, Jackson |
| Database | H2 by default; MySQL and PostgreSQL; GaussDB via PostgreSQL protocol; OceanBase via MySQL protocol |
| Frontend | Vue 3, TypeScript, Vite, Element Plus, Pinia, Vue Router, Axios, ECharts |
| Packaging | Maven multi-module build; `frontend-maven-plugin` pins Node/npm and builds frontend assets |
| Deployment | Single JAR or Docker Compose |

## Quick Start

### Docker Compose

```bash
cd docker
cp .env.example .env
# Edit .env and change JASYPT_ENCRYPTOR_PASSWORD / LITE_ALERT_JWT_SECRET / LITE_ALERT_APIKEY_PEPPER
docker compose up -d
```

The default setup uses an H2 file database under `docker/data`. Open:

```text
http://localhost:8080
```

Health check:

```text
GET http://localhost:8080/api/health
```

### MySQL

Enable MySQL in `docker/.env`:

```env
COMPOSE_PROFILES=mysql
LITE_ALERT_DATASOURCE_URL=jdbc:mysql://mysql:3306/lite_alert?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true
LITE_ALERT_DATASOURCE_USERNAME=root
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver
LITE_ALERT_DATABASE_TYPE=mysql
MYSQL_ROOT_PASSWORD=litealert
```

Then run:

```bash
docker compose up -d
```

### PostgreSQL

Enable PostgreSQL in `docker/.env`:

```env
COMPOSE_PROFILES=postgres
LITE_ALERT_DATASOURCE_URL=jdbc:postgresql://postgres:5432/lite_alert
LITE_ALERT_DATASOURCE_USERNAME=postgres
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=org.postgresql.Driver
LITE_ALERT_DATABASE_TYPE=postgresql
POSTGRES_PASSWORD=litealert
```

GaussDB and OceanBase examples are available in `docker/.env.example`.

## Local Development

Backend tests:

```bash
mvn -pl backend -am test -Dskip.frontend=true
```

Frontend type check and build:

```bash
cd frontend
npm run type-check
npm run build
```

Full package:

```bash
mvn -pl backend -am package
```

When frontend build is not skipped, Maven downloads the pinned Node/npm versions, builds the frontend, and copies static assets into the backend classpath.

## Webhook Example

Header mode, recommended:

```bash
curl -X POST "http://localhost:8080/api/webhook/demo/order_paid" \
  -H "Authorization: Bearer la_xxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","amount":99.5}'
```

Query mode, for limited callers:

```bash
curl -X POST "http://localhost:8080/api/webhook/demo/order_paid?key=la_xxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","amount":99.5}'
```

A successful response is `200 OK`, meaning the request has been accepted and delivery jobs have been created. Check audit logs or delivery records for final delivery results.

## Project Layout

```text
lite-alert/
├── backend/                 # Spring Boot backend
├── frontend/                # Vue 3 frontend
├── docker/                  # Dockerfile, docker-compose, env examples
└── docs/design/             # System design documents
```

## Design Documents

- [Overview](docs/design/00-overview.md)
- [Architecture and Data Flow](docs/design/01-architecture.md)
- [Data Model and Storage](docs/design/02-data-model.md)
- [Authentication and Authorization](docs/design/03-auth.md)
- [Namespace and Topic](docs/design/04-namespace-topic.md)
- [Message Transform](docs/design/05-message-transform.md)
- [Notification Channels](docs/design/06-notify-channel.md)
- [Webhook API](docs/design/07-webhook-api.md)
- [Frontend](docs/design/08-frontend.md)
- [Deployment](docs/design/09-deploy.md)
- [Roadmap](docs/design/10-roadmap.md)
- [ApiKey Management](docs/design/11-apikey.md)

## Security Notes

- Change `JASYPT_ENCRYPTOR_PASSWORD`, `LITE_ALERT_JWT_SECRET`, and `LITE_ALERT_APIKEY_PEPPER` before production use.
- Changing `LITE_ALERT_APIKEY_PEPPER` invalidates all existing ApiKeys unless a dedicated migration or rotation process is used.
- ApiKey plaintext is shown only once after create or rotate. The server never stores it and cannot recover it.
- Prefer `Authorization` headers over query parameters for ApiKeys.
- Do not log raw Webhook payloads, ApiKey plaintext, or notification target secrets.
