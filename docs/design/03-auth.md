# 03 · 认证与权限

## 1. 角色

| 角色 | 来源 | 权限 |
| --- | --- | --- |
| ADMIN | application.yml 内 bootstrap + 后续 ADMIN 创建 | 全部功能，包括用户管理 |
| USER | ADMIN 在前端创建 | 仅管理自己的命名空间/Topic/邮箱 |

## 2. 初始管理员配置

`application.yml`：

```yaml
lite-alert:
  bootstrap:
    admin:
      username: admin
      # Jasypt 加密后的 BCrypt hash
      password: ENC(xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx)
```

加载流程：
1. 启动时读取 `lite-alert.bootstrap.admin`
2. Jasypt 解密 → 得到 BCrypt hash（即直接落库的 `passwordHash`，不需明文）
3. 若 `users.json` 内不存在该用户名 → 创建；存在则跳过
4. 管理员可在前端「用户管理」中修改密码（仅修改文件层，不回写 yml）

> 这样配置文件里**永远只出现 hash 的 ENC 形态**，主密钥靠 `JASYPT_ENCRYPTOR_PASSWORD` 注入。

## 3. 密码存储

- 入库前：`BCrypt.hashpw(rawPassword, BCrypt.gensalt(10))`
- 存盘时：在 BCrypt hash 外再套一层 Jasypt（防止脱库后被离线穷举弱口令）
- 校验时：Jasypt 解密 → BCrypt.checkpw

## 4. 登录与会话

- 接口：`POST /api/auth/login` { username, password } → 返回 `{token, expiresAt, user}`
- Token：JWT，HS256，密钥同样由环境变量 `LITE_ALERT_JWT_SECRET` 注入
- 有效期：默认 8 小时，前端在过期前 5 分钟静默刷新（`POST /api/auth/refresh`）
- 注销：客户端丢 token；服务端维护一个内存级黑名单（短 TTL）防止泄露 token 在到期前被复用

## 5. 鉴权过滤器

- `JwtAuthFilter`：解析 Authorization 头，注入 `SecurityContext`
- `WebhookAuthFilter`：仅对 `/api/webhook/**` 生效，使用 X-Webhook-Key
- 两条链互不交叉，避免相互污染

## 6. 接口权限矩阵（节选）

| 接口 | ADMIN | USER | 调用方 |
| --- | --- | --- | --- |
| `/api/users/**` | ✅ | ❌ | ❌ |
| `/api/namespaces` GET | ✅ 全部 | ✅ 仅自己 | ❌ |
| `/api/namespaces` POST | ✅ | ✅ | ❌ |
| `/api/topics/**` | ✅ 全部 | ✅ 仅自己命名空间下 | ❌ |
| `/api/contacts/**` | ✅ 自己 | ✅ 自己 | ❌ |
| `/api/webhook/{ns}/{t}` | ❌ | ❌ | ✅ X-Webhook-Key |

## 7. 安全细节

- 登录失败计数（基于用户名）：连续 5 次失败锁定 15 分钟
- 所有写操作前置 CSRF：因为前后端同源 + JWT，CSRF 通过「JWT 在 Header」天然规避
- 每个 ADMIN 创建用户必须配合一次性强密码 + 强制首次登录修改
- 审计：所有用户管理动作落 `audit/auth-yyyy-MM.log`
