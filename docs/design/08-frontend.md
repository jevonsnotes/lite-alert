# 08 · 前端页面规划

## 1. 技术选型

- Vue 3 + `<script setup lang="ts">` + TypeScript。
- Vite 构建，Maven 打包时输出并复制到后端静态资源。
- Element Plus 组件库。
- Pinia 管理登录态、主题偏好等状态。
- Vue Router，全局守卫做登录与权限校验。
- Axios 封装在 `frontend/src/http`，统一 `/api` 前缀、JWT 注入和错误提示。
- ECharts 用于 Dashboard 统计图表。

> 当前依赖中未包含 monaco editor，JSON Schema / 模板编辑以现有组件实现；如后续需要源码编辑增强，再引入懒加载编辑器。

## 2. 路由结构

```text
/login                     登录页
/                          需登录，AppLayout
├── /dashboard             首页统计看板
├── /namespaces            命名空间列表与创建
├── /topics/:id            Topic 详情
├── /apikeys               ApiKey 管理
├── /contacts              通知目标
├── /audit                 审计日志查询
├── /admin/users           USER_VIEW: 用户管理
├── /admin/roles           ROLE_VIEW: 角色管理
└── /admin/system          SYSTEM_SETTINGS_VIEW: 系统设置
```

路由守卫规则：

- 未登录访问非公开页面 → 跳转 `/login`。
- `meta.permission` 页面要求当前用户拥有对应权限。

## 3. 关键页面交互

### 3.1 登录

- 登录成功后保存 JWT 与用户信息。
- 登录失败给出明确提示。
- 访问受保护页面时带 `redirect` 参数，登录后返回原页面。

### 3.2 Dashboard

展示系统概览：

- 命名空间数、Topic 数、今日调用量、失败数。
- 调用趋势图。
- Topic 或通知目标排行。

### 3.3 命名空间

- 表格展示 name、描述、状态、Topic 数、创建时间。
- 新建时实时校验命名规则。
- 删除或禁用前二次确认。

### 3.4 Topic 详情

建议 Tab：

| Tab | 功能 |
| --- | --- |
| 基础信息 | name、description、status、发布/禁用/启用 |
| 报文格式 | JSON Schema 编辑与校验 |
| 通道模板 | 按 EMAIL / DINGTALK / FEISHU / WECOM / WEBHOOK 配置 subject、body、出站模板 |
| 报文转换 | JSONPath mapping 表、示例报文、dry-run 输出 |
| 订阅 | 勾选当前用户通知目标并保存 |
| 安全与接入 | keyLocation、IP 白名单、限流、覆盖当前 Topic 的 ApiKey、cURL 示例 |
| 调用/投递记录 | 展示 traceId、状态、目标、失败原因、payload 权限控制 |

### 3.5 ApiKey 管理

- 列表：名称、prefix、授权范围、有效期、状态、最近使用、使用次数、限流、操作。
- 新建：名称、生效/失效时间、授权范围、限流配置。
- 创建成功：完整 key 只展示一次，关闭后不可再查看。
- 轮换：`POST /api/apikeys/{id}/rotate`，返回新 key，旧 key 立即失效或按后端策略替换。
- 撤销 / 删除：二次确认。

### 3.6 通知目标

- 类型卡片：邮件、钉钉、飞书、企业微信、通用 Webhook。
- endpoint 脱敏展示。
- secret 只允许写入/更新，不回显明文。
- 不同类型展示不同输入提示。

### 3.7 用户与角色管理

- 用户管理：创建、启用/禁用、重置密码、删除。
- 角色管理：查看权限列表、创建角色、编辑权限、删除非内置角色。
- 角色页面入口需要 `ROLE_VIEW` 权限。

### 3.8 系统设置

- SMTP 配置查看/保存/删除。
- SMTP 测试发送。
- 系统限流、安全开关等设置。
- 高风险变更需要二次确认。

## 4. 通用组件

| 组件 | 用途 |
| --- | --- |
| JSON 编辑/校验组件 | 编辑 inboundFormat、示例报文、Webhook 模板 |
| JsonPath 输入 | 编辑 transform mapping 的 `from` 字段 |
| MaskedText | 展示脱敏 endpoint、prefix 等 |
| Confirmable | 危险操作二次确认 |
| PermissionGate | 根据权限控制按钮或页面块展示 |

## 5. 与后端的契约

- 全部通过 `frontend/src/http` 的 Axios 实例访问。
- baseURL 保持 `/api`。
- 401：清理登录态并跳转登录。
- 403：提示无权限。
- 5xx：展示 traceId，方便排查。
- 涉及 ApiKey、secret、payload 的页面必须遵守后端脱敏与权限规则。
