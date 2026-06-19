# 05 · 报文转换（Message Transform）

> 用 JSONPath 字段映射、模板变量和出站模板，把入站报文转换为通知模板上下文或通用 Webhook 出站报文。

## 1. 当前定位

转换能力已经从旧版 Topic 顶层 `transform` 下沉到 `Topic.templates[NotifyTarget.Type]`：

- EMAIL / DINGTALK / FEISHU / WECOM：主要使用 `subject` / `body` 的 Mustache 模板渲染。
- WEBHOOK：可使用 `outputTemplate`、`outputXmlTemplate`、`transform.mappings` 组合生成出站请求体，并支持响应断言。
- 旧字段 `Topic.transform` / `Topic.notifyTemplate` 只作为兼容字段读取。

## 2. 设计原则

- **通道专属**：不同通知通道可有不同模板和转换规则。
- **声明式转换**：字段提取通过 JSONPath + mapping 表完成，不执行用户脚本。
- **模板负责拼接**：字符串拼接放在 Mustache 模板中，转换层只负责结构化取值与写入。
- **敏感安全**：转换失败、断言失败、日志记录均不得泄露 ApiKey 原文和敏感目标配置。
- **可调试**：Topic 详情页可通过 dry-run 预览模板输出。

## 3. Transform 数据结构

```json
{
  "enabled": true,
  "mappings": [
    {
      "from": "$.orderId",
      "to": "orderId",
      "type": "string",
      "required": true
    },
    {
      "from": "$.amount",
      "to": "amount",
      "type": "number",
      "defaultValue": 0
    },
    {
      "from": "$.tags[*]",
      "to": "tags",
      "type": "array<string>"
    },
    {
      "from": "$",
      "to": "raw",
      "type": "json"
    }
  ]
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `from` | JSONPath，表示从入站报文中取值 |
| `to` | 输出对象路径，支持点号路径 |
| `type` | `string`、`number`、`boolean`、`json`、`array<...>` |
| `required` | 取值失败时是否整体失败 |
| `defaultValue` | 取值为空时使用的默认值 |

## 4. 执行语义

1. 创建输出对象 `result = {}`。
2. 对每条 mapping 执行 JSONPath。
3. 若取值为空：
   - 有 `defaultValue` 则使用默认值；
   - 无默认值且 `required=true` 则转换失败；
   - 无默认值且非必填则跳过或写入 null。
4. 按 `type` 做类型转换。
5. 将值写入 `to` 指向的位置。
6. 返回输出对象与每条 mapping 的 trace。

## 5. 与 Mustache 模板的关系

模板上下文由以下数据合并而来：

- 入站原始 JSON。
- transform 输出对象。
- 系统变量。

系统变量包括：

| 变量 | 说明 |
| --- | --- |
| `namespace` | 命名空间名 |
| `topic` | Topic 名 |
| `traceId` | 本次调用追踪 id |
| `receivedAt` | 接收时间 |
| `rawJson` | 报文 JSON 字符串 |

示例：

```text
subject: 订单 {{orderId}} 已支付
body:    <h3>{{orderId}}</h3><p>金额：{{amount}}</p><pre>{{rawJson}}</pre>
```

## 6. WEBHOOK 出站模板

WEBHOOK 通道可把 Lite-Alert 作为“入站 Webhook → 出站 Webhook”的桥接器。

### JSON 输出

```json
{
  "outputFormat": "JSON",
  "outputTemplate": {
    "msgType": "order_paid",
    "traceId": "{{traceId}}",
    "data": {}
  },
  "transform": {
    "enabled": true,
    "mappings": [
      { "from": "$.orderId", "to": "data.orderId", "type": "string", "required": true },
      { "from": "$.amount", "to": "data.amount", "type": "number" }
    ]
  }
}
```

执行顺序：先渲染 `outputTemplate` 中的 Mustache 变量，再用 mapping 结果覆盖对应字段。

### XML 输出

```json
{
  "outputFormat": "XML",
  "outputXmlTemplate": "<message><trace>{{traceId}}</trace><title>{{title}}</title></message>"
}
```

XML 模板适用于对接只接受 XML 的外部系统。

## 7. WEBHOOK 响应断言

WEBHOOK 通道可配置 `responseCheck` 判断下游是否真正成功：

```json
{
  "enabled": true,
  "bodyType": "AUTO",
  "successPath": "$.code",
  "operator": "EQ",
  "successValue": "0",
  "messagePath": "$.message"
}
```

| 字段 | 说明 |
| --- | --- |
| `bodyType` | `AUTO` / `JSON` / `XML` |
| `successPath` | JSONPath 或 XML 路径 |
| `operator` | `EQ` / `NE` / `CONTAINS` / `REGEX` / `GT` / `LT` / `EXISTS` |
| `successValue` | 期望值 |
| `messagePath` | 失败消息路径 |

断言失败时，投递应标记为失败并进入重试或终态失败流程。

## 8. 后端接口

| 接口 | 用途 |
| --- | --- |
| `POST /api/topics/{id}/template/dry-run` | 传入示例报文，返回模板渲染与转换预览 |
| `PATCH /api/topics/{id}` | 保存 templates、inboundFormat、安全参数等 Topic 配置 |

## 9. 安全边界

- JSONPath 只做数据读取，不允许用户脚本。
- 报文大小默认限制为 64 KB。
- mapping 条数应设置合理上限，避免复杂转换拖垮单次请求。
- dry-run 与审计输出默认脱敏，不展示 ApiKey 原文和通知目标 secret。
