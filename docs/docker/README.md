# Lite-Alert

A lightweight, file-encrypted, all-in-one notification service with built-in H2 database, pluggable external databases (MySQL / PostgreSQL / GaussDB / OceanBase), and a Vue 3 admin dashboard.

[![GitHub](https://img.shields.io/github/v/tag/jevonsnotes/lite-alert?label=version)](https://github.com/jevonsnotes/lite-alert)
[![Docker Pulls](https://img.shields.io/docker/pulls/jevonsnotes/lite-alert)](https://hub.docker.com/r/jevonsnotes/lite-alert)

---

## Docker Deployment

### 1. Prepare the Environment

Clone the repository and enter the `docker/` directory:

```bash
git clone https://github.com/jevonsnotes/lite-alert.git
cd lite-alert/docker
cp .env.example .env
```

Edit `.env` and **fill in the three required secrets**:

```ini
JASYPT_ENCRYPTOR_PASSWORD=<your-32-char-min-secret>
LITE_ALERT_JWT_SECRET=<your-32-char-min-secret>
LITE_ALERT_APIKEY_PEPPER=<your-32-char-min-secret>
```

### 2. Choose a Database Mode

Database selection is controlled entirely via `COMPOSE_PROFILES` and JDBC variables in `.env`. No command-line flags required.

#### 🟢 Mode A: H2 File Database (Default, Zero Config)

No changes needed. Data is stored in `./data`.

```ini
# .env (default state)
COMPOSE_PROFILES=
LITE_ALERT_DATASOURCE_URL=jdbc:h2:file:/data/lite-alert;MODE=MySQL;AUTO_SERVER=TRUE
LITE_ALERT_DATASOURCE_USERNAME=sa
LITE_ALERT_DATASOURCE_DRIVER=org.h2.Driver
LITE_ALERT_DATABASE_TYPE=h2
```

#### 🔵 Mode B: MySQL 8.0 (Built-in Container)

Uncomment the MySQL section in `.env` and set `COMPOSE_PROFILES=mysql`:

```ini
# .env
COMPOSE_PROFILES=mysql
LITE_ALERT_DATASOURCE_URL=jdbc:mysql://mysql:3306/lite_alert?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true
LITE_ALERT_DATASOURCE_USERNAME=root
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver
LITE_ALERT_DATABASE_TYPE=mysql
MYSQL_ROOT_PASSWORD=litealert
```
Compose will automatically start a `mysql:8.0` container alongside the app. Data persists to `./mysql-data`.

#### 🟠 Mode C: PostgreSQL 16 (Built-in Container)

Uncomment the PostgreSQL section in `.env` and set `COMPOSE_PROFILES=postgres`:

```ini
# .env
COMPOSE_PROFILES=postgres
LITE_ALERT_DATASOURCE_URL=jdbc:postgresql://postgres:5432/lite_alert
LITE_ALERT_DATASOURCE_USERNAME=postgres
LITE_ALERT_DATASOURCE_PASSWORD=litealert
LITE_ALERT_DATASOURCE_DRIVER=org.postgresql.Driver
LITE_ALERT_DATABASE_TYPE=postgresql
POSTGRES_PASSWORD=litealert
```
Compose will automatically start a `postgres:16-alpine` container. Data persists to `./postgres-data`.

#### 🟣 Mode D: External Database (GaussDB / OceanBase / Self-hosted)

Leave `COMPOSE_PROFILES` empty (or unset). Point the JDBC URL to your existing database:

```ini
# .env
COMPOSE_PROFILES=
# GaussDB example (uses PostgreSQL protocol)
LITE_ALERT_DATASOURCE_URL=jdbc:postgresql://your-gaussdb-host:5432/lite_alert
LITE_ALERT_DATASOURCE_USERNAME=your-user
LITE_ALERT_DATASOURCE_PASSWORD=your-password
LITE_ALERT_DATASOURCE_DRIVER=org.postgresql.Driver
LITE_ALERT_DATABASE_TYPE=gaussdb

# OceanBase example (uses MySQL protocol)
# LITE_ALERT_DATASOURCE_URL=jdbc:mysql://your-oceanbase-host:2881/lite_alert?useSSL=false&serverTimezone=Asia/Shanghai
# LITE_ALERT_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver
# LITE_ALERT_DATABASE_TYPE=oceanbase
```

### 3. Deploy

**Standard Docker Compose:**
```bash
cd lite-alert/docker
docker compose up -d
```

**One-Click / Wrapper Apps:**  
If your deployment platform provides a "Deploy" button for `docker-compose.yml`, just ensure the `.env` file is correctly configured beforehand. The `COMPOSE_PROFILES` variable in `.env` tells Docker Compose which database container to activate automatically.

**Verify deployment:**
```bash
docker compose ps
docker compose logs -f lite-alert
curl http://localhost:8080/api/health
```

### 4. Access the Dashboard

Open `http://localhost:8080` in your browser.

| Username | Password |
|----------|----------|
| `admin`  | `admin123` |

> ⚠️ Change the admin password immediately after first login.

---

## Single Container Deployment (`docker run`)

If you only want the app container (using an external database you manage yourself):

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
  -v $(pwd)/data:/data \
  jevonsnotes/lite-alert:latest
```

---

## Environment Variables

### 🔐 Required Secrets
| Variable | Description |
|----------|-------------|
| `JASYPT_ENCRYPTOR_PASSWORD` | Master key for decrypting `ENC(...)` values in config files |
| `LITE_ALERT_JWT_SECRET` | JWT signing secret (HS256), must be ≥ 32 characters |
| `LITE_ALERT_APIKEY_PEPPER` | Salt for API key hashing. Set once, **never rotate** in production |

### 🗄️ Database Configuration
| Variable | Default | Description |
|----------|---------|-------------|
| `LITE_ALERT_DATASOURCE_URL` | `jdbc:h2:file:/data/lite-alert;...` | Full JDBC connection URL |
| `LITE_ALERT_DATASOURCE_USERNAME` | `sa` | Database username |
| `LITE_ALERT_DATASOURCE_PASSWORD` | *(empty)* | Database password |
| `LITE_ALERT_DATASOURCE_DRIVER` | `org.h2.Driver` | JDBC driver class |
| `LITE_ALERT_DATABASE_TYPE` | `h2` | Logical DB type: `h2`, `mysql`, `postgresql`, `gaussdb`, `oceanbase` |

### ⚙️ Advanced / Optional
| Variable | Default | Description |
|----------|---------|-------------|
| `COMPOSE_PROFILES` | *(empty)* | Set to `mysql` or `postgres` to activate bundled DB containers |
| `IMAGE` | `jevonsnotes/lite-alert:${VERSION}` | Override Docker Hub image name/tag |
| `VERSION` | `latest` | Image tag suffix when `IMAGE` is not set |
| `PORT` | `8080` | HTTP port exposed on the host |
| `TZ` | `Asia/Shanghai` | Container timezone |
| `MYSQL_ROOT_PASSWORD` | `litealert` | Root password for the bundled MySQL container |
| `POSTGRES_PASSWORD` | `litealert` | Password for the bundled PostgreSQL container |

---

## Data Persistence

| Mode | Volume Mount | What's Stored |
|------|--------------|---------------|
| H2 | `./data` | H2 file database (`lite-alert.mv.db`) |
| MySQL | `./mysql-data` | MySQL 8.0 data directory |
| PostgreSQL | `./postgres-data` | PostgreSQL 16 data directory |

> ⚠️ Never delete the `data/`, `mysql-data/`, or `postgres-data/` directories after deployment. Doing so will permanently erase all configuration and message data.

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

## Build Locally

```bash
docker build -t lite-alert:local -f docker/Dockerfile .
```

---

## License

[MIT](https://github.com/jevonsnotes/lite-alert)
