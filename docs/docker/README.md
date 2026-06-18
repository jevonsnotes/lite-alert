# Lite-Alert

A lightweight, file-encrypted, all-in-one notification service with built-in H2 database, pluggable external databases (MySQL / PostgreSQL / GaussDB / OceanBase), and a Vue 3 admin dashboard.

[![GitHub](https://img.shields.io/github/v/tag/jevonsnotes/lite-alert?label=version)](https://github.com/jevonsnotes/lite-alert)
[![Docker Pulls](https://img.shields.io/docker/pulls/jevonsnotes/lite-alert)](https://hub.docker.com/r/jevonsnotes/lite-alert)

---

## Quick Start

### H2 File Database (default, zero config)

```bash
docker run -d --name lite-alert \
  -p 8080:8080 \
  -e JASYPT_ENCRYPTOR_PASSWORD=your-32-char-min-secret \
  -e LITE_ALERT_JWT_SECRET=your-32-char-min-secret \
  -e LITE_ALERT_APIKEY_PEPPER=your-32-char-min-pepper \
  -v $(pwd)/data:/data \
  jevonsnotes/lite-alert:latest
```

Visit `http://localhost:8080`, login with default credentials: `admin` / `admin123`.

### Docker Compose (recommended)

```bash
# Clone the repository
git clone https://github.com/jevonsnotes/lite-alert.git
cd lite-alert/docker

# Copy and configure .env
cp .env.example .env
# Edit .env: fill in the three required secrets

# Start with H2 database
docker compose up -d

# Or start with MySQL
docker compose --profile mysql up -d

# Or start with PostgreSQL
docker compose --profile postgres up -d
```

---

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `JASYPT_ENCRYPTOR_PASSWORD` | **Yes** | — | Jasypt encryption key for `ENC(...)` values |
| `LITE_ALERT_JWT_SECRET` | **Yes** | — | JWT signing secret (≥ 32 chars) |
| `LITE_ALERT_APIKEY_PEPPER` | **Yes** | — | API key hash pepper (≥ 32 chars, never rotate in prod) |
| `LITE_ALERT_DATASOURCE_URL` | No | `jdbc:h2:file:/data/lite-alert;...` | JDBC connection URL |
| `LITE_ALERT_DATASOURCE_USERNAME` | No | `sa` | Database username |
| `LITE_ALERT_DATASOURCE_PASSWORD` | No | — | Database password |
| `LITE_ALERT_DATASOURCE_DRIVER` | No | `org.h2.Driver` | JDBC driver class |
| `LITE_ALERT_DATABASE_TYPE` | No | `h2` | Database type: `h2`, `mysql`, `postgresql`, `gaussdb`, `oceanbase` |
| `PORT` | No | `8080` | HTTP server port |
| `TZ` | No | `Asia/Shanghai` | Timezone |

---

## Database Switch

### MySQL

```bash
docker run -d --name lite-alert \
  -p 8080:8080 \
  -e JASYPT_ENCRYPTOR_PASSWORD=your-secret \
  -e LITE_ALERT_JWT_SECRET=your-secret \
  -e LITE_ALERT_APIKEY_PEPPER=your-pepper \
  -e LITE_ALERT_DATASOURCE_URL=jdbc:mysql://host:3306/lite_alert?useSSL=false&serverTimezone=Asia/Shanghai \
  -e LITE_ALERT_DATASOURCE_USERNAME=root \
  -e LITE_ALERT_DATASOURCE_PASSWORD=db-password \
  -e LITE_ALERT_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver \
  -e LITE_ALERT_DATABASE_TYPE=mysql \
  jevonsnotes/lite-alert:latest
```

### PostgreSQL

```bash
docker run -d --name lite-alert \
  -p 8080:8080 \
  -e JASYPT_ENCRYPTOR_PASSWORD=your-secret \
  -e LITE_ALERT_JWT_SECRET=your-secret \
  -e LITE_ALERT_APIKEY_PEPPER=your-pepper \
  -e LITE_ALERT_DATASOURCE_URL=jdbc:postgresql://host:5432/lite_alert \
  -e LITE_ALERT_DATASOURCE_USERNAME=postgres \
  -e LITE_ALERT_DATASOURCE_PASSWORD=db-password \
  -e LITE_ALERT_DATASOURCE_DRIVER=org.postgresql.Driver \
  -e LITE_ALERT_DATABASE_TYPE=postgresql \
  jevonsnotes/lite-alert:latest
```

> **GaussDB** uses the same PostgreSQL connection. **OceanBase** uses the same MySQL connection (port `2881` by default).

---

## Docker Compose Profiles

| Profile | Database | Command |
|---------|----------|---------|
| *(default)* | H2 file | `docker compose up -d` |
| `mysql` | MySQL 8.0 container | `docker compose --profile mysql up -d` |
| `postgres` | PostgreSQL 16 container | `docker compose --profile postgres up -d` |

When using `mysql` or `postgres` profile, the database container is included in compose. For **GaussDB** or **OceanBase** (external databases), use the default profile and configure the JDBC URL in `.env`.

---

## Features

- **Namespace isolation** — multi-tenant data separation
- **Topic management** — custom topics with JSON schema validation
- **Webhook ingestion** — public webhook endpoints with rate limiting and IP whitelisting
- **Email notifications** — SMTP-based email delivery
- **Webhook notifications** — outbound webhook targets
- **Subscription engine** — flexible topic → target subscriptions
- **API Key auth** — per-key rate limiting, scope control
- **Admin dashboard** — Vue 3 SPA with ECharts analytics
- **Audit logging** — operation audit trail
- **Jasypt encryption** — encrypted config values in YAML

---

## Default Admin Account

| Username | Password |
|----------|----------|
| `admin` | `admin123` |

> Change the password after first login. In production, set a bcrypt-hashed password via `ENC(...)` in `application-prod.yml`.

---

## Health Check

```bash
curl http://localhost:8080/api/health
```

---

## Build Locally

```bash
docker build -t lite-alert:local -f docker/Dockerfile .
```

Or use the release script:

```bash
./docker/release.sh --version 1.0.0 --push
```

---

## License

[MIT](https://github.com/jevonsnotes/lite-alert)
