# 03 · 认证与权限

## 1. 账号与角色

Lite-Alert 采用 JWT 登录 + RBAC 权限模型。

| 概念 | 说明 |
| --- | --- |
| User | 登录账号，包含用户名、密码哈希、启用状态、基础角色或角色引用 |
| Role | 权限集合，可由管理员维护；内置角色不可随意删除 |
| Permission | 细粒度权限字符串，如 `TOPIC_VIEW`、`APIKEY_CREATE`、`ROLE_UPDATE` |
| ADMIN / USER | 历史基础角色仍保留，用于兼容和快速判断管理员能力 |

## 2. 初始管理员

启动时由配置引导创建初始管理员。生产环境必须使用加密后的配置值和安全密钥：

```yaml
lite-alert:
  jwt:
    secret: ${LITE_ALERT_JWT_SECRET}
  apikey:
    pepper: ${LITE_ALERT_APIKEY_PEPPER}

jasypt:
  encryptor:
    password: ${JASYPT_ENCRYPTOR_PASSWORD}
```

管理员创建后可在前端「用户管理」中维护用户，在「角色管理」中维护角色与权限。

## 3. 密码存储

- 入库前：对明文密码做 BCrypt 哈希。
- 落库时：`passwordHash` 再经过 Jasypt 字段加密。
- 校验时：Jasypt 解密 → BCrypt 校验。
- 重置密码：管理员生成新密码后只展示一次。

## 4. 登录与会话

| 接口 | 说明 |
| --- | --- |
| `POST /api/auth/login` | 用户名密码登录，返回 `{token, expiresAt, user}` |
| `GET /api/auth/me` | 根据 JWT 返回当前登录用户、角色与权限信息 |

Token 使用 JWT HS256，密钥来自 `LITE_ALERT_JWT_SECRET`。前端通过 Axios 统一注入 `Authorization: Bearer <jwt>`。

## 5. 鉴权过滤器

- `JwtAuthFilter`：解析后台管理接口的 JWT，注入 `SecurityContext`。
- Webhook 调用方不使用后台 JWT，而是在 `WebhookService` 中通过 `ApiKeyAuthenticator` 校验调用方 ApiKey。
- `/api/webhook/**` 与 `/api/auth/login` 等公开入口需要在 Spring Security 中放行，再由各自业务流程做认证。

## 6. 权限矩阵（节选）

| 接口 | 主要权限 | 说明 |
| --- | --- | --- |
| `/api/auth/login` | 公开 | 登录 |
| `/api/auth/me` | 已登录 | 当前用户信息 |
| `/api/users/**` | `USER_VIEW` / `USER_CREATE` / `USER_UPDATE` / `USER_DELETE` | 用户管理 |
| `/api/roles/**` | `ROLE_VIEW` / `ROLE_CREATE` / `ROLE_UPDATE` / `ROLE_DELETE` | 角色管理 |
| `/api/namespaces/**` | `NAMESPACE_*` | 用户只能操作自己的命名空间；管理员可查看全部 |
| `/api/topics/**` | `TOPIC_*` | Topic CRUD、发布、禁用、模板 dry-run |
| `/api/apikeys/**` | `APIKEY_*` | ApiKey 列表、新建、编辑、撤销、轮换、删除 |
| `/api/contacts/**` | `CONTACT_*` | 通知目标管理 |
| `/api/deliveries/**` | `DELIVERY_VIEW`，payload 需 `DELIVERY_PAYLOAD_READ` | 投递记录与敏感报文查看 |
| `/api/audit` | `AUDIT_VIEW` | 审计查询 |
| `/api/admin/settings` | `SYSTEM_SETTINGS_VIEW` / `SYSTEM_SETTINGS_UPDATE` | 系统设置 |
| `/api/admin/mail-config` | `SYSTEM_SETTINGS_VIEW` / `SYSTEM_SETTINGS_UPDATE` | SMTP 配置 |
| `/api/webhook/{namespace}/{topic}` | ApiKey scope | 外部调用方入口，不使用后台 JWT |

权限常量以 `auth.permission.Permissions` 为准。

## 7. 安全细节

- ApiKey 原文不得写入日志、审计、数据库或异常信息。
- 用户密码与通知目标 endpoint / secret 均属于敏感字段，默认加密和脱敏。
- URL 参数传递 ApiKey 仅用于兼容受限调用方，推荐优先使用请求头。
- 高风险操作（删除、撤销、轮换、系统设置修改）前端必须二次确认，后端写审计。
- `DELIVERY_PAYLOAD_READ` 单独拆分，避免普通运维人员默认看到原始业务报文。
