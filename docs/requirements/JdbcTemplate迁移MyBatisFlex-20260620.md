# JdbcTemplate 迁移 MyBatis-Flex 需求分析

**日期**：2026-06-20

## 需求概述

将项目当前持久化层从 JdbcTemplate 全面迁移到 MyBatis-Flex，统一持久化框架。

- 简单 CRUD 模块用 MyBatis-Flex `BaseMapper` + `QueryWrapper`。
- 含 JSON 字段的模块用 MyBatis-Flex TypeHandler 统一处理。
- 复杂统计 / 聚合查询保留手写 SQL，但改为 MyBatis-Flex 原生 SQL 调用，避免混合风格。

## 技术可行性结论

MyBatis-Flex 1.10.9 已明确支持项目目标数据库：H2、MySQL、PostgreSQL、OceanBase、GaussDB（按 PostgreSQL 兼容模式处理）。

当前项目已引入 `mybatis-flex-spring-boot3-starter`，只需按模块逐个迁移 Store 层即可。

## 推荐方案及理由

### 分层迁移策略

按复杂度分为三层：

| 层 | 模块 | 方式 |
|---|---|---|
| 第 1 层 | NamespaceStore、SubscriptionStore、UserStore、NotifyTargetStore | 简单 CRUD → BaseMapper + QueryWrapper |
| 第 2 层 | TopicStore、ApiKeyStore | 含 JSON 字段 → TypeHandler 统一序列化 |
| 第 3 层 | StatsController、StatsService、WebhookService、RateLimiter | 复杂聚合 → 保留 SQL，改调用方式 |

### 理由

1. 简单 CRUD 迁移风险低，可以验证迁移流程。
2. JSON 字段是核心差异点，用 TypeHandler 统一处理可避免多数据库兼容问题。
3. 复杂统计查询手写 SQL 更可控，迁移收益不高，保留即可。

## 风险点和注意事项

1. **JSON 字段多库差异**：H2 用 CLOB，MySQL 有 JSON 类型。需 TypeHandler 统一处理。
2. **H2 默认启动**：迁移后优先验证 H2 模式，确保单 JAR 开箱即用。
3. **测试覆盖**：每次迁移一个模块后，先跑相关测试确认通过。

## 预估工作量

约 3–5 个工作日，按模块分批迁移。

## 确认结果

- 用户确认全量迁移，不再逐次确认。
- 按第 1 层 → 第 2 层 → 第 3 层顺序推进，每次迁移后跑测试。
