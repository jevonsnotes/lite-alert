# api-task-runner

API 类型定时任务的执行定义、HTTP 调用、多条件响应断言、Cron 调度与执行结果落库。

## ADDED Requirements

### Requirement: HTTP 请求定义

API 任务 SHALL 支持配置 HTTP 方法（GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS）、请求地址、自定义请求头与请求体。系统 SHALL 在执行时按配置构造并发起请求。

#### Scenario: 发起 GET 请求

- **WHEN** 一个 API 任务配置方法为 `GET`、地址为某 URL，到达 Cron 触发时刻
- **THEN** 系统对该 URL 发起 GET 请求，不带请求体
- **AND** 记录响应状态码与响应体

#### Scenario: 自定义请求头被携带

- **WHEN** 任务配置了请求头 `Authorization: Bearer xxx`
- **THEN** 执行时该请求头随请求一起发送

### Requirement: 请求体类型与自动 Content-Type

请求体 SHALL 支持以下类型：`none`、`form-data`（multipart）、`x-www-form-urlencoded`、`raw`。`raw` 下 SHALL 支持子类型 `json`、`xml`、`text`。系统 SHALL 根据请求体类型自动设置对应的 `Content-Type` 头；当用户显式提供了 `Content-Type` 头时，SHALL 以用户提供的值为准。

#### Scenario: raw-json 自动生成 Content-Type

- **WHEN** 任务请求体类型为 `raw` 且子类型为 `json`，用户未显式设置 Content-Type
- **THEN** 系统自动添加请求头 `Content-Type: application/json`

#### Scenario: raw-xml 自动生成 Content-Type

- **WHEN** 任务请求体类型为 `raw` 且子类型为 `xml`，用户未显式设置 Content-Type
- **THEN** 系统自动添加请求头 `Content-Type: application/xml`

#### Scenario: form-data 自动生成 Content-Type

- **WHEN** 任务请求体类型为 `form-data`
- **THEN** 系统以 multipart 方式发送，并自动生成带 boundary 的 `Content-Type: multipart/form-data; boundary=...`

#### Scenario: 用户显式 Content-Type 优先

- **WHEN** 用户在请求头中显式设置了 `Content-Type`
- **THEN** 系统使用用户提供的值，不覆盖

#### Scenario: none 类型不带请求体

- **WHEN** 任务请求体类型为 `none`
- **THEN** 请求不携带请求体，也不自动设置 Content-Type

### Requirement: 多条件响应断言

API 任务 SHALL 支持配置响应体断言：每个条件由「提取路径（JSONPath 或 XPath）+ 操作符 + 期望值」组成，操作符支持 `等于 / 不等于 / 包含 / 正则 / 大于 / 小于 / 存在`。系统 SHALL 支持配置多个条件，并提供逻辑模式 `AND`（全部满足）与 `OR`（任一满足）组合条件。断言通过的判定 SHALL 为：HTTP 状态码为 2xx，且条件按所选逻辑模式成立。

#### Scenario: AND 模式全部满足判定成功

- **WHEN** 任务配置断言逻辑为 `AND`，条件为 `$.code 等于 0` 与 `$.data.status 等于 ok`，响应同时满足两者
- **THEN** 该次执行断言通过，判定为成功

#### Scenario: AND 模式部分不满足判定失败

- **WHEN** 任务配置断言逻辑为 `AND`，含两个条件，响应只满足其一
- **THEN** 该次执行断言失败，判定为失败并记录未满足的条件

#### Scenario: OR 模式任一满足判定成功

- **WHEN** 任务配置断言逻辑为 `OR`，含两个条件，响应满足其中任意一个
- **THEN** 该次执行断言通过，判定为成功

#### Scenario: HTTP 非 2xx 直接判定失败

- **WHEN** 任务响应状态码为 500，无论断言条件如何
- **THEN** 该次执行判定为失败

#### Scenario: 未配置断言时按状态码判定

- **WHEN** 任务未配置任何断言条件
- **THEN** 系统仅依据 HTTP 状态码（2xx）判定成功与否

### Requirement: Cron 调度与发布即生效

调度引擎 SHALL 按任务的已发布配置与 Cron 表达式维护调度。发布（首次发布或重新发布）SHALL 触发该任务调度的重建（取消旧调度、按新配置建立新调度），无需重启服务。

#### Scenario: 首次发布建立调度

- **WHEN** 一个任务首次被发布，Cron 为每 5 分钟
- **THEN** 调度器为其建立按 Cron 周期触发的调度
- **AND** 到达触发时刻按已发布配置执行一次

#### Scenario: 重新发布热更新调度

- **WHEN** 一个已发布任务被再次发布（Cron 或请求配置已改动）
- **THEN** 调度器取消旧调度并按新配置重建
- **AND** 重建过程不中断当前周期之外的其它任务调度

#### Scenario: 服务重启后自动恢复调度

- **WHEN** 服务重启
- **THEN** 调度引擎在启动时加载所有 `PUBLISHED` 任务的已发布配置并重建调度

### Requirement: 执行结果落库

每次 API 任务触发执行 SHALL 生成一条调用记录，记录任务 ID、触发时间、HTTP 方法、请求地址、HTTP 状态码、耗时、断言是否通过、错误信息，以及按脱敏策略保留的响应体摘要。

#### Scenario: 成功执行生成成功记录

- **WHEN** 一次执行 HTTP 2xx 且断言通过
- **THEN** 生成一条状态为成功的调用记录，含状态码、耗时与响应体摘要

#### Scenario: 失败执行生成失败记录

- **WHEN** 一次执行因网络错误、HTTP 非 2xx 或断言不通过而失败
- **THEN** 生成一条状态为失败的调用记录，含错误信息与（如可得的）状态码
