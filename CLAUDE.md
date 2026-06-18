# Lite-Alert 项目工作规范

## 项目简介

Lite-Alert 是一个默认内置 H2、可切换外部数据库、前后端一体的轻量级消息通知服务。目标是单 JAR 启动，默认开箱即用，并可在生产环境切换 MySQL / PostgreSQL / GaussDB / OceanBase。

## 技术栈

- 后端：Java 17、Spring Boot 3.5、Spring Security、Spring Validation、Spring Mail、MyBatis-Flex、JDBC、H2/MySQL/PostgreSQL、Jasypt、Caffeine、Jackson、Maven。
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、Axios、ECharts。
- 存储：默认 H2 数据库，可通过配置切换 MySQL、PostgreSQL、GaussDB（PostgreSQL 兼容模式）和 OceanBase（MySQL 兼容模式）；复杂业务字段以 JSON 文本存储。
- 打包：Maven 多模块，前端构建产物输出到后端静态资源目录，最终以单 JAR 交付。

## 目录约定

- `backend/`：Spring Boot 后端模块。
  - `src/main/java/io/litealert/auth`：认证、用户、JWT、安全过滤器。
  - `src/main/java/io/litealert/namespace`：命名空间管理。
  - `src/main/java/io/litealert/topic`：Topic、报文格式、转换规则。
  - `src/main/java/io/litealert/apikey`：ApiKey 生命周期、鉴权相关元数据。
  - `src/main/java/io/litealert/notify`：通知目标、通知渠道、订阅与派发。
  - `src/main/java/io/litealert/webhook`：Webhook 接入、鉴权、限流、白名单。
  - `src/main/java/io/litealert/admin`：管理端系统设置、统计、审计入口。
  - `src/main/java/io/litealert/common`：通用配置、错误、加密、存储、工具类。
- `frontend/`：Vue 3 前端。
  - `src/views`：页面级组件。
  - `src/components`：可复用组件。
  - `src/http`：Axios 封装。
  - `src/stores`：Pinia 状态。
  - `src/router`：路由配置。
- `docs/design/`：系统设计文档，是需求和架构判断的主要依据。
- `docs/requirements/`：新需求分析与确认文档。
- `docs/bugs/`：缺陷记录、根因分析与处理记录。

## 开发约束

- 所有新功能优先参考 `docs/design/` 的既有架构和术语，避免引入与设计文档冲突的概念。
- 后端保持按业务域分包，Controller 只负责接口适配，业务规则放在 Service，持久化细节放在 Store。
- 数据持久化以数据库为准；默认 H2，生产可切换外部 MySQL / PostgreSQL / GaussDB / OceanBase。
- 敏感信息不得打印到日志；ApiKey 原文只允许创建后一次性返回，服务端不得存储或再次展示原文。
- 前端页面使用 Vue 3 `<script setup lang="ts">`，UI 组件优先使用 Element Plus，图表使用 ECharts。
- 前端接口统一通过 `frontend/src/http` 封装访问，保持 `/api` 前缀契约。
- 代码风格应贴合现有代码：Java 使用 Lombok、构造器注入、record 请求体；Vue 使用组合式 API、局部 scoped 样式。

## 测试与验证

- 新功能和修复遵循 TDD：先补充能够暴露需求差异或缺陷的测试，再实现功能，最后确认测试转绿。
- 后端优先增加 JUnit/Spring Boot 测试，覆盖 Service 业务规则与 Controller 接口契约。
- 前端当前未配置单元测试框架时，至少执行 `npm run type-check` 和 `npm run build`；涉及关键交互时补充可执行验证说明。
- 常用验证命令：
  - 后端：`mvn -pl backend -am test -Dskip.frontend=true`
  - 前端类型检查：`cd frontend && npm run type-check`
  - 前端构建：`cd frontend && npm run build`
  - 一体打包：`mvn -pl backend -am package`

## 提交流程

- 不在用户未要求时主动提交或推送代码。
- 若用户要求提交，commit message 使用英文 Conventional Commits，例如：
  - `test: add api key edit coverage`
  - `feat: support api key and notify target management improvements`
  - `fix: correct dashboard trend aggregation`
- 每次功能开发完成后，输出中文变更摘要、测试结果和未完成风险。

## 产品沟通规范

- 与产品经理沟通使用中文。
- 新需求先形成 `docs/requirements/` 需求分析文档，经确认后进入开发。
- 缺陷必须先记录到 `docs/bugs/` 并完成根因分析，再进入修复。
