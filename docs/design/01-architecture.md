# 01 · 架构与数据流

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        浏览器 (Vue3)                          │
│  登录 / 用户管理 / 命名空间 / Topic / 通知目标 / Webhook 调试  │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTPS / 同源
┌──────────────────────────▼──────────────────────────────────┐
│                Spring Boot 3 (单 JAR)                        │
│ ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐   │
│ │ Static  │  │ /api/**  │  │/api/web  │  │ Scheduler    │   │
│ │ (Vue)   │  │ (mgmt)   │  │ hook/**  │  │ (健康/重试)   │   │
│ └─────────┘  └────┬─────┘  └────┬─────┘  └──────┬───────┘   │
│                   │             │                │           │
│                   ▼             ▼                ▼           │
│            ┌─────────────────────────────────────────┐      │
│            │  Service 层 (auth/topic/notify/...)     │      │
│            └────────────────┬────────────────────────┘      │
│                             │                                │
│            ┌────────────────▼─────────────────┐              │
│            │  Storage 层 (FileStore + 加密)    │              │
│            │  JSON 文件 + Caffeine 内存索引    │              │
│            └────────────────┬─────────────────┘              │
└─────────────────────────────┼────────────────────────────────┘
                              │
                  ┌───────────▼────────────┐
                  │  /data 数据卷           │
                  │  ├── users.json        │
                  │  ├── namespaces.json   │
                  │  ├── topics/{ns}.json  │
                  │  ├── contacts.json     │
                  │  └── audit/yyyymm.log  │
                  └────────────────────────┘
                              │
                              ▼
                       ┌──────────────┐
                       │ SMTP Server  │
                       └──────────────┘
```

## 2. 关键请求时序

### 2.1 管理后台修改 Topic
```
Browser ──POST /api/topics──▶ TopicController
                              └─▶ TopicService.update()
                                   ├─▶ 校验命名空间归属 + Topic 唯一性
                                   ├─▶ TopicStore.save()  // 写文件 + 重建索引
                                   └─▶ 返回新版本号
```

### 2.2 外部应用调用 Webhook (核心链路)
```
Caller ──POST /api/webhook/{ns}/{topic}
        Header: X-Webhook-Key=xxx
        Body  : {...}
   │
   ▼
WebhookController
   │ 1. 限流 / IP 白名单 (可选)
   │ 2. 解析 ns + topic，从内存索引取 Topic 配置
   │ 3. 校验 webhookKey (常量时间比较)
   │ 4. 校验 Topic 状态 = PUBLISHED
   ▼
ValidatorService
   │ 5. 用 Topic.inboundFormat (JSON Schema) 校验报文
   ▼
TransformService           (可选，Topic 配置了转换规则才执行)
   │ 6. 按 JSONPath 映射表生成 outbound 报文
   ▼
NotifyDispatcher
   │ 7. 取 Topic 关联的邮箱列表 (从 ContactStore)
   │ 8. 渲染邮件主题/正文模板 (Mustache)
   │ 9. 投递到 EmailChannel(异步线程池)
   ▼
EmailChannel
   │ 10. JavaMailSender 发送
   │ 11. 失败 → 写重试队列（内存 + 落盘 audit）
   ▼
返回 202 Accepted (附 traceId)
```

## 3. 线程与并发模型

| 组件 | 模型 |
| --- | --- |
| HTTP 接入 | Tomcat 默认线程池 |
| 通知派发 | 独立 `taskExecutor` (核心 4，最大 16，队列 1000) |
| 文件写入 | 每类数据单独 `ReentrantReadWriteLock`，读多写少 |
| 重试 | `ScheduledExecutorService`，固定 30s 扫描内存重试队列 |

## 4. 失败与降级

- SMTP 不可达 → 入重试队列，最多 5 次指数退避，最终失败写 audit 日志
- 文件写入失败 → 抛出业务异常，回滚内存中的脏改动
- 报文 Schema 校验失败 → 直接 4xx，不进入派发链路
- 转换规则执行异常 → 回退使用原始报文 + 在审计日志中标记 transformError=true

## 5. 可观测性

- `/api/admin/health` 自检：磁盘可写 / SMTP 探活 / 索引大小
- 审计日志 `audit/yyyy-MM.log`：每次 webhook 调用一行 JSON（脱敏后）
- 前端「调用记录」页直接读最近 N 行审计日志
