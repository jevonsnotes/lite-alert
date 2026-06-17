# 08 · 前端页面规划

## 1. 技术选型

- Vue 3 + `<script setup>` + TypeScript
- Vite 构建，输出到 `backend/src/main/resources/static`
- Element Plus 全量按需引入（unplugin-auto-import）
- Pinia 管理用户状态、命名空间缓存
- Vue Router，全局守卫做登录校验
- axios 封装，自动注入 JWT、统一错误提示
- monaco editor（仅在 Schema/转换页面懒加载）

## 2. 路由结构

```
/login                     登录页
/                          需登录
├── /dashboard             首页：我的命名空间数 / Topic 数 / 今日通知数 / 失败数
├── /namespaces            命名空间列表 + 新建
│   └── /:nsId             命名空间详情（其下 Topic 列表）
├── /topics/:id            Topic 详情（多 Tab：基础信息 / 报文格式 / 转换 / 模板 / 订阅 / 安全与接入 / 调用记录）
├── /apikeys               ApiKey 管理（新建 / 编辑 scope / 撤销 / 删除）
├── /contacts              通知目标（邮件 / 钉钉 / 飞书 / 企业微信）
├── /admin/users           ADMIN: 用户管理
├── /admin/system          ADMIN: 系统设置（SMTP、限流、密钥重置）
└── /audit                 审计日志查询
```

## 3. 关键页面交互

### 3.1 登录
- 失败给明确提示：账号/密码错误 vs 锁定中
- 「记住我」= 把 token 存 localStorage（默认 sessionStorage）

### 3.2 命名空间列表
- 表格：name / 描述 / Topic 数 / 创建时间 / 操作
- 新建对话框：输入 name 时实时校验正则
- 删除前 confirm：列出该命名空间下的 Topic 数

### 3.3 Topic 详情（最复杂）
Tab 设计：

| Tab | 功能 |
| --- | --- |
| 基础信息 | name / status / 描述 / 发布按钮 |
| 报文格式 | JSON Schema 编辑（可视化 + 源码切换），保存即校验 |
| 报文转换 | 三栏：左示例报文、中映射表、右目标报文预览（500ms 节流 dryRun） |
| 通知模板 | subject / body 模板编辑，右侧预览 |
| 订阅 | 当前用户 NotifyTarget 列表 + 复选框，保存触发 PUT |
| 安全与接入 | **鉴权模式**(API_KEY/NONE 切换) / **IP 白名单** / **限流** / 关联 ApiKey 列表 / cURL 示例 |
| 调用记录 | 表格分页，点击行展开看 payload + 通知结果 + 所用 ApiKey |

#### 「安全与接入」Tab 关键交互

- **鉴权模式**：单选 API_KEY / NONE
  - 切换到 NONE 时弹确认：「此 Topic 将公开可调用，强烈建议配合 IP 白名单」
  - 普通 USER 若未开启权限，NONE 选项置灰并提示
- **关联 ApiKey 列表**（仅 API_KEY 模式可见）：
  - 列出当前用户名下、scope 覆盖本 Topic 的 ApiKey（含按命名空间间接覆盖）
  - 每行显示 名称 / 前缀 / 有效期 / 状态 / 「撤销」按钮
  - 顶部「+ 新建 ApiKey 并授权此 Topic」直接跳转 `/apikeys` 并预填 scope
  - 没有任何关联 ApiKey 时显示空态 + 引导
- **IP 白名单**：CIDR 输入，每行一条，前端做格式校验
- **cURL 示例**：实时根据当前模式 + 选中的 ApiKey 渲染（API_KEY 模式带 `Authorization: Bearer ...`，NONE 模式不带）

### 3.4 通知目标
- 类型选择卡片：邮件 / 钉钉 / 飞书 / 企业微信群机器人
- label / 地址脱敏展示（邮箱 `a***@b.com`、Webhook URL 显示 host + 末尾 6 位）
- 不同类型显示不同 placeholder 与可选 secret 字段（DingTalk 加签）
- 「测试发送」按钮（M8 后续），结果以 toast 反馈

### 3.5 ApiKey 管理（详见文档 11 §8）
- 列表：名称 / Key 前缀 `la_8f3a••••••••` / 授权范围气泡 / 有效期 / 状态 / 最近使用 / 操作
- 新建对话框：名称、生效/失效时间（含「永久有效」复选框）、授权范围（按命名空间穿梭框 + 按 Topic 树形多选）
- 提交后：**只展示一次**完整 key 弹窗，含「复制」「我已保存」按钮，关闭即不可再看
- 撤销 / 删除二次确认；状态影响按钮可用性

### 3.6 用户管理（ADMIN）
- 新建用户：用户名 + 一次性密码（系统生成或手动输入），生成后弹窗只展示一次
- 启用 / 禁用 / 重置密码 / 删除

## 4. 通用组件

- `<JsonEditor>`：基于 monaco，封装 Schema 校验、错误下划线
- `<JsonPathInput>`：带补全的 JSONPath 输入
- `<MaskedText>`：自动脱敏的文本组件
- `<Confirmable>`：危险操作二次确认弹窗

## 5. 样式与设计感

- 主色：以 `#3D7CFF` 为主，避免默认蓝调过强
- 暗色模式：Element Plus 自带 + 用户偏好持久化
- 表格密度：默认紧凑，长列表更友好

## 6. 与后端的契约

- 全部通过 axios 实例 `http`，baseURL = `/api`
- 响应统一形态：
  ```json
  { "code": "OK", "data": ... }
  { "code": "INVALID_PAYLOAD", "message": "...", "errors": [...] }
  ```
- 401 → 自动跳登录；403 → 全局 toast；5xx → 带 traceId 的 toast，可点击复制
