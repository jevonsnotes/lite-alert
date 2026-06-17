# 05 · 报文转换（Message Transform）

> 用 JSONPath 字段映射表，把入站报文按字段挑选 / 重命名 / 类型转换，
> 组装成一份"目标对象"供通知模板使用。

## 1. 设计原则

- **职责单一**：转换只负责"取值 → 写入目标键"，不做字符串拼接。
  字符串拼接（"订单 12345 已支付"）放到通知模板的 Mustache 里完成。
- **零脚本**：所有转换通过声明式映射完成，避免 sandbox 风险。
- **可视化**：前端是一张普通表格，from / to / type / required / default。
- **类型安全**：每条映射声明目标类型；运行时按目标类型强制转换。
- **幂等**：纯函数，相同输入相同输出。

## 2. 数据结构

```json
{
  "enabled": true,
  "mappings": [
    {
      "from": "$.orderId",            // JSONPath，源端取值
      "to":   "orderId",              // 目标对象的键路径，"a.b" → 嵌套对象
      "type": "string",
      "required": true
    },
    {
      "from": "$.amount",
      "to":   "amount",
      "type": "number",
      "default": 0
    },
    {
      "from": "$.user.name",
      "to":   "buyer",
      "type": "string",
      "default": "匿名"
    },
    {
      "from": "$.tags[*]",            // 多值 → 数组
      "to":   "tags",
      "type": "array<string>"
    },
    {
      "from": "$",                    // 整个原报文
      "to":   "raw",
      "type": "json"
    }
  ]
}
```

> 历史字段 `outputTemplate` / `${path}` 占位符已移除：它和字段映射会
> 互相覆盖，且字符串拼接交给通知模板更清晰。旧数据中如有该字段，加载
> 时会被忽略；保存时不再写入。

## 3. 执行语义

1. 创建一个空目标对象 `result = {}`。
2. 对每条 mapping：
   1. 用 JSONPath 在入站报文上求值（库：`com.jayway.jsonpath`）。
   2. 求值结果为 null/空：
      - 若声明了 `default`，使用默认值；
      - 否则 mapping 标记为 `failed`，若 `required=true` 整个转换失败。
   3. 类型转换：
      - `string`：String.valueOf
      - `number`：Double / Long
      - `boolean`：true/false 解析
      - `array<T>`：JSONPath 多值结果 → 列表，每个元素再做 T 转换
      - `json`：保持原 JsonNode
   4. 把转换后的值写入 `result` 在 `to` 指向的位置：
      - `to: "orderId"` → `result.orderId = ...`
      - `to: "user.name"` → `result.user.name = ...`（自动创建中间对象）
      - `to: "tags[2]"` → 数组下标写入
      - `to: ""` 且值是对象 → 合并到 `result` 根
3. 返回 `result`，连同每条 mapping 的命中 trace。

## 4. 失败处理

| 情形 | 行为 |
| --- | --- |
| JSONPath 求值异常 | 该 mapping 标记 failed；若 `required` 整体失败 |
| `required` 缺失且无 default | 整体转换失败，调用方收到 400 `TRANSFORM_FAILED` |
| 类型转换失败 | 该字段写入 `null`；其他字段照常 |
| `enabled=false` 或 mappings 为空 | 直接透传原始报文 |

## 5. 与通知模板的关系

转换后的目标对象作为 Mustache 模板的渲染上下文：

- 通知模板 `notifyTemplate.body` 中可写 `{{orderId}}`、`{{buyer}}`、`{{level}}` 等
- 字符串拼接（"订单 {{orderId}} 已支付"）在模板里完成，**不要**在转换层做
- 若 `transform.enabled=false`，模板上下文 = 原始入站报文 + 系统变量

系统变量（任何上下文都附带）：
- `namespace` 命名空间名
- `topic` Topic 名
- `traceId` 本次调用追踪 id
- `receivedAt` 受理时间（ISO-8601）
- `rawJson` 转换后报文的 JSON 字符串

## 6. 前端交互（要点）

- 顶部说明：
  > 如果只想拼接字符串（"订单 12345 已支付"），不需要转换 ——
  > 直接在「通知模板」里用 `{{orderId}}` 即可。
- 启用开关 + 预设按钮（不转换 / 简单告警映射）
- 字段映射表：from（JSONPath）/ to / type / required / default
- 试运行：左侧填示例报文 → 后端 dryRun → 右侧显示输出 + 每条映射的命中结果
- 输出区域文案：「输出（即通知模板拿到的上下文）」，让用户明白这里是给模板用的

## 7. 后端接口

| 接口 | 用途 |
| --- | --- |
| `POST /api/topics/{id}/transform/dry-run` | 入参 = 示例报文，返回 `{ schemaOk, output, traces[] }` |
| `PATCH /api/topics/{id}` 携带 `transform` 字段 | 保存转换规则（DRAFT 或 PUBLISHED 都可改） |

`traces` 每项形如：

```json
{ "from": "$.orderId", "to": "orderId", "ok": true, "failed": false, "message": null }
```

## 8. 安全

- JSONPath 库使用纯解析模式（`Option.SUPPRESS_EXCEPTIONS` + `DEFAULT_PATH_LEAF_TO_NULL`），不执行任意脚本
- 报文大小上限：默认 64 KB（NONE-mode Topic 32 KB；可配置 `lite-alert.webhook.max-body-size`）
- mappings 条数上限：默认 100
- 转换异常不会泄露原始报文 —— audit 中只记录 `transformError=true` 与 mapping id

## 9. 完整示例

入站报文：
```json
{
  "orderId": "ORD-12345",
  "amount": 99.5,
  "user": { "name": "张三" },
  "tags": ["vip", "monthly"]
}
```

字段映射：
```json
{
  "enabled": true,
  "mappings": [
    { "from": "$.orderId",   "to": "orderId", "type": "string", "required": true },
    { "from": "$.amount",    "to": "amount",  "type": "number" },
    { "from": "$.user.name", "to": "buyer",   "type": "string", "default": "匿名" },
    { "from": "$.tags[*]",   "to": "tags",    "type": "array<string>" }
  ]
}
```

转换输出（即通知模板的上下文）：
```json
{
  "orderId": "ORD-12345",
  "amount": 99.5,
  "buyer": "张三",
  "tags": ["vip", "monthly"]
}
```

通知模板：
```
subject: 订单 {{orderId}} 已支付（{{buyer}}）
body:    <h3>{{orderId}}</h3>
         <p>金额：{{amount}} 元</p>
         <p>买家：{{buyer}}</p>
```

最终邮件主题：`订单 ORD-12345 已支付（张三）`
