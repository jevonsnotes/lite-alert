# 10 · 实施路线图

> 按里程碑组织，每个里程碑都是「可演示、可上线」的最小闭环。

## M0 · 工程脚手架（0.5 天）

- 仓库结构 / Maven 多模块 / Vite 工程
- Dockerfile 多阶段 + docker-compose 跑通空壳
- CI（可选）：GitHub Actions / 本地 build 脚本

> 完成标志：浏览器访问 `:8080` 能看到 Vue 默认页

## M1 · 认证骨架（1 天）

- application.yml + Jasypt 配置加密
- 启动初始化 admin
- 登录 / JWT 过滤器 / 用户 CRUD（仅 ADMIN）
- 前端 Login + Layout + 路由守卫

> 完成标志：admin 登录 → 创建一个普通用户 → 切换登录正常

## M2 · 文件存储基座（1 天）

- `FileStore` 抽象 + 原子写 + 读写锁
- Caffeine 索引、`@Encrypted` 字段加密的 Jackson Module
- audit 日志框架

> 完成标志：单元测试覆盖 CRUD、并发写、崩溃半写恢复

## M3 · 命名空间 + Topic（1.5 天）

- 命名空间唯一性、CRUD
- Topic CRUD + 状态机（`auth.mode` 字段，含 NONE 模式风险护栏）
- 前端：命名空间列表、Topic 列表 + Tab 框架

> 完成标志：能创建命名空间、Topic，发布 / 禁用流转正常，NONE 模式有二次确认

## M3.5 · ApiKey 模块（1 天，与 M3 可并行）

- ApiKey 实体 + HMAC 哈希 + 内存 prefixIndex
- CRUD 接口、scope 校验、撤销与删除流程
- 前端「ApiKey 管理」页：列表 / 新建对话框 / 一次性显示弹窗
- Topic 详情「安全与接入」Tab 集成 ApiKey 选择器

> 完成标志：可独立创建 / 撤销 ApiKey，授权范围按 Topic 与 namespace 都能勾选生效

### M3.6 · Webhook 鉴权模式升级

- 去掉 NONE 免认证模式，所有 Topic 统一 ApiKey 认证
- 新增 `keyLocation=HEADER|QUERY`，支持请求头 / URL 参数两种传 key 方式
- 旧 NONE Topic 在后续使用中需手动重新配置 keyLocation
- Topic 详情 cURL 示例根据 keyLocation 自动生成
- 响应码从 202 Accepted 改为 200 OK（兼容只认 200 的外部系统）

## M4 · 报文格式 + 转换（2 天）

- JSON Schema 校验集成
- JSONPath 转换引擎 + dryRun 接口
- 前端：可视化 + 源码切换、转换三栏布局

> 完成标志：dryRun 能在前端立即看到目标报文与每行命中状态

## M5 · 邮箱 + 通知派发（1.5 天）

- NotifyTarget CRUD（多通道）+ 测试发送
- Topic 订阅复选框
- NotifyDispatcher（异步线程池 + 重试 + audit）
- Mustache 模板渲染

> 完成标志：调用 webhook → SMTP 真实收到邮件，audit 完整记录

## M6 · Webhook 接入正式版（1 天）

- 接入 ApiKey 鉴权（Authorization: Bearer）+ scope 校验 + 失败计数
- NONE 模式（IP 白名单 + 限流）落地
- 标准错误体 / Trace 贯穿（区分 INVALID / EXPIRED / REVOKED / SCOPE_NOT_ALLOWED）
- 接入文档自动生成（cURL / Schema / 字段表 / ApiKey 选择器）
- 前端「调用记录」页（含所用 ApiKey）

> 完成标志：拿一份示例报文 + cURL，业务侧无人工对接也能跑通；ApiKey 撤销即时生效

## M7 · 部署与运维（1 天）

- docker-compose 完整版、健康检查、备份脚本
- 系统设置页（SMTP 在线编辑、限流参数）
- 性能压测（webhook 1k QPS 内）

> 完成标志：一台干净机器 `docker compose up -d` 全跑通

## M8 · 打磨（持续）

- 暗色模式、键盘快捷键
- 多语言（i18n）骨架
- 渠道扩展（钉钉 / 飞书 / SMS）

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 主密钥泄露 → 数据全失密 | 主密钥仅 ENV、不写文件；提供「批量重加密迁移」工具 |
| ApiKey pepper 泄露 | pepper 仅 ENV、不可轮换（轮换 = 全部 ApiKey 失效）；提供「批量撤销并引导重建」应急流程 |
| 文件并发写损坏 | 临时文件 + 原子 rename + 备份 .bak |
| SMTP 不稳定 | 重试队列 + 限频 + 失败 audit |
| 误删命名空间 | 二次确认 + 拒绝级联（存在 PUBLISHED Topic 时） |
| 误启用 NONE 模式 | 发布二次确认 + audit 记录 + 默认仅 ADMIN 可建（已废弃 NONE 模式，统一 API_KEY 认证） |
| 报文超大撑爆内存 | body 大小限制 + JSON 解析失败即拒绝 |

## 估时合计

约 **10–11 个工作日**完成 M0–M7 主线（含独立的 M3.5 ApiKey 模块），剩余打磨项分阶段迭代。
