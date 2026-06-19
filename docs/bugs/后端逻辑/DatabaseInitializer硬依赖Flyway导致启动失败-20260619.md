---
severity: P1
status: fixed
first_seen: 2026-06-19
occurrences:
  - date: 2026-06-19
    description: DatabaseInitializer 使用 @DependsOn("flywayInitializer") 后，IDE 启动环境缺少 flywayInitializer bean，导致应用启动失败。
---

# DatabaseInitializer 硬依赖 flywayInitializer 导致启动失败

## 根因分析

- 涉及后端初始化类：`backend/src/main/java/io/litealert/common/db/DatabaseInitializer.java`。
- 数据流转链路：应用启动 → Spring 创建 `DatabaseInitializer` bean → `@DependsOn("flywayInitializer")` 要求容器中必须存在名为 `flywayInitializer` 的 bean → 当前 IDE 启动环境未创建该 bean → Spring 启动失败。
- 根本原因：为了解决空库中 `la_user` 早于 Flyway 建表被查询的问题，采用了硬依赖 `flywayInitializer` 的方式；但该 bean 并非在所有启动环境中都一定存在，导致启动鲁棒性不足。

## 解决方案

- 移除 `@DependsOn("flywayInitializer")` 硬依赖。
- `DatabaseInitializer` 启动时先检查 `la_user` 是否存在。
- 如果表已存在，直接初始化默认管理员。
- 如果表不存在，执行当前数据库类型对应的 `db/schema-*.sql` 作为兜底初始化，再初始化默认管理员。
- 保持 Flyway 作为主迁移机制；兜底 schema 用于 IDE 未加载 Flyway 或 Flyway 未执行的启动环境。

## 处理记录

| 日期 | 出现模块/场景 | 尝试方案 | 结果 | 说明 |
|------|-------------|----------|------|------|
| 2026-06-19 | IDE 启动 / Spring bean 创建 | 移除 `@DependsOn("flywayInitializer")`，改为运行时检查 `la_user` 表；若不存在则执行 `schema-*.sql` 兜底初始化 | 有效 | `DatabaseInitializerTest` 验证空库可完成 Flyway V1/V2 后初始化默认管理员 |
