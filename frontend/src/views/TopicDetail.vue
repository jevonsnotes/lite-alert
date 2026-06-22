<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch, put, del } from '@/http'
import { formatDateTime } from '@/utils/datetime'
import SchemaFieldRow from '@/components/SchemaFieldRow.vue'
import {
  type SchemaField,
  schemaToFields,
  fieldsToSchema
} from '@/utils/jsonSchema'

type ChannelType = 'EMAIL' | 'DINGTALK' | 'FEISHU' | 'WECOM' | 'WEBHOOK'

type Mapping = {
  from: string
  to: string
  type: 'string' | 'number' | 'boolean' | 'json' | 'array<string>' | 'array<number>'
  required?: boolean
  defaultValue?: any
}
type Transform = { enabled: boolean; mappings: Mapping[] }

type WebhookResponseCheck = {
  enabled: boolean
  bodyType: 'AUTO' | 'JSON' | 'XML'
  successPath: string
  successValue: string
  messagePath?: string
  operator: 'EQ' | 'NE' | 'CONTAINS' | 'REGEX' | 'GT' | 'LT' | 'EXISTS'
}

type ChannelTemplate = {
  subject?: string
  body?: string
  outputFormat?: 'JSON' | 'XML'
  outputTemplate?: any          // WEBHOOK only: outbound JSON template
  outputXmlTemplate?: string    // WEBHOOK only: outbound XML template
  responseCheck?: WebhookResponseCheck
  transform?: Transform         // WEBHOOK only: field mappings
}

type WebhookTemplate = ChannelTemplate & {
  responseCheck: WebhookResponseCheck
  transform: Transform
}

type TemplateState = Record<Exclude<ChannelType, 'WEBHOOK'>, ChannelTemplate> & { WEBHOOK: WebhookTemplate }

type Topic = {
  id: string
  namespaceId: string
  namespaceName: string
  name: string
  description?: string
  status: 'DRAFT' | 'PUBLISHED' | 'DISABLED'
  auth: { mode: 'API_KEY' | 'NONE'; keyLocation?: 'HEADER' | 'QUERY'; ipWhitelist: string[]; rateLimit?: any }
  inboundFormat?: any
  templates?: Partial<Record<ChannelType, ChannelTemplate>>
  sync?: boolean
  syncTimeout?: number | null
  createdAt?: string
  publishedAt?: string
}

type ApiKeyView = { id: string; name: string; prefix: string; status: string; validUntil?: string }
type Contact = { id: string; type: ChannelType; label: string; endpoint: string; enabled: boolean }
type Settings = { perTopicPerMinute?: number }

const route = useRoute()
const router = useRouter()

const topic = ref<Topic | null>(null)
const originalTopicName = ref('')
const topicDefaultLimit = ref(60)
const isNew = computed(() => route.params.id === '__new__' || route.params.id === '__create__')
const tabName = ref('basic')

// ============ NEW TOPIC FORM ============
const draftCreate = reactive({
  name: '',
  description: '',
  sync: false,
  authMode: 'API_KEY' as 'API_KEY' | 'NONE',
  keyLocation: 'HEADER' as 'HEADER' | 'QUERY',
  ipWhitelist: '' as string,
  inboundFormat:
    '{\n  "type": "object",\n  "required": ["title"],\n  "properties": {\n    "title": { "type": "string" },\n    "level": { "type": "string", "enum": ["info", "warn", "error"] },\n    "message": { "type": "string" }\n  }\n}'
})

// ============ SCHEMA TAB ============
const schemaMode = ref<'visual' | 'source'>('visual')
const schemaJson = ref('')
const visualFields = ref<SchemaField[]>([])
const schemaError = ref<string | null>(null)

function syncSchemaJsonToVisual() {
  schemaError.value = null
  try {
    const obj = JSON.parse(schemaJson.value || '{}')
    visualFields.value = schemaToFields(obj)
  } catch (e: any) {
    schemaError.value = e.message
    visualFields.value = []
  }
}
function syncVisualToSchemaJson() {
  schemaJson.value = JSON.stringify(fieldsToSchema(visualFields.value), null, 2)
}
function addRootField() {
  visualFields.value.push({ name: '', type: 'string', required: false })
}
function onSchemaModeChange(next: 'visual' | 'source') {
  if (next === 'visual') syncSchemaJsonToVisual()
  schemaMode.value = next
}

const SCHEMA_PRESETS: { label: string; sample: string }[] = [
  {
    label: '通用告警',
    sample: JSON.stringify({
      type: 'object',
      required: ['title'],
      properties: {
        title:   { type: 'string', description: '告警标题' },
        level:   { type: 'string', enum: ['info', 'warn', 'error'] },
        message: { type: 'string', description: '详情正文' }
      }
    }, null, 2)
  },
  {
    label: '订单（含嵌套买家）',
    sample: JSON.stringify({
      type: 'object',
      required: ['orderId', 'amount'],
      properties: {
        orderId: { type: 'string' },
        amount:  { type: 'number' },
        currency:{ type: 'string', enum: ['CNY', 'USD'] },
        buyer:   {
          type: 'object',
          properties: {
            name: { type: 'string' },
            mobile: { type: 'string' }
          }
        },
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              sku: { type: 'string' },
              qty: { type: 'integer' }
            }
          }
        }
      }
    }, null, 2)
  }
]
function applySchemaPreset(s: string) {
  schemaJson.value = s
  if (schemaMode.value === 'visual') syncSchemaJsonToVisual()
}

// ============ CHANNEL TEMPLATES TAB ============
type ChannelDef = {
  value: ChannelType
  label: string
  hasSubject: boolean
  hasBody: boolean
  hasOutputTemplate: boolean    // WEBHOOK only
  hasTransform: boolean         // WEBHOOK only
}
const CHANNELS: ChannelDef[] = [
  { value: 'EMAIL',    label: '邮件',           hasSubject: true,  hasBody: true,  hasOutputTemplate: false, hasTransform: false },
  { value: 'DINGTALK', label: '钉钉',           hasSubject: true,  hasBody: true,  hasOutputTemplate: false, hasTransform: false },
  { value: 'FEISHU',   label: '飞书',           hasSubject: true,  hasBody: true,  hasOutputTemplate: false, hasTransform: false },
  { value: 'WECOM',    label: '企业微信',       hasSubject: true,  hasBody: true,  hasOutputTemplate: false, hasTransform: false },
  { value: 'WEBHOOK',  label: 'Webhook',        hasSubject: false, hasBody: false, hasOutputTemplate: true,  hasTransform: true  }
]
const channelTab = ref<ChannelType>('EMAIL')
const currentChannel = computed(() => CHANNELS.find(c => c.value === channelTab.value)!)

function emptyWebhookTemplate(): WebhookTemplate {
  return { transform: { enabled: false, mappings: [] }, responseCheck: { enabled: false, bodyType: 'AUTO', successPath: '', successValue: '', messagePath: '', operator: 'EQ' } }
}

const templates = ref<TemplateState>({
  EMAIL: {}, DINGTALK: {}, FEISHU: {}, WECOM: {},
  WEBHOOK: emptyWebhookTemplate()
})

const TEMPLATE_PRESETS: TemplateState = {
  EMAIL: {
    subject: '[{{namespace}}] {{topic}} · {{title}}',
    body:
`<h3 style="color:#3d7cff">{{title}}</h3>
<p>{{message}}</p>
<table style="font-size:13px">
  <tr><td style="color:#888">命名空间</td><td>{{namespace}}</td></tr>
  <tr><td style="color:#888">Topic</td><td>{{topic}}</td></tr>
  <tr><td style="color:#888">级别</td><td>{{level}}</td></tr>
  <tr><td style="color:#888">买家姓名</td><td>{{$.buyer.name}}</td></tr>
  <tr><td style="color:#888">追踪 ID</td><td><code>{{traceId}}</code></td></tr>
</table>`
  },
  DINGTALK: {
    subject: '[{{namespace}}] {{topic}}',
    body:
`### {{title}}

> {{message}}

- 级别：{{level}}
- 命名空间：{{namespace}}
- 追踪 ID：{{traceId}}`
  },
  FEISHU: {
    subject: '[{{namespace}}] {{topic}}',
    body:
`**{{title}}**

{{message}}

级别：{{level}}
追踪 ID：{{traceId}}`
  },
  WECOM: {
    subject: '[{{namespace}}] {{topic}}',
    body:
`### {{title}}
{{message}}

级别：<font color="warning">{{level}}</font>
命名空间：{{namespace}}`
  },
  WEBHOOK: {
    outputFormat: 'JSON',
    outputTemplate: {
      title: '{{title}}',
      level: '{{level}}',
      message: '{{message}}',
      namespace: '{{namespace}}',
      topic: '{{topic}}',
      traceId: '{{traceId}}',
      id: '{{uuid}}',
      receivedAt: '{{receivedAt}}'
    },
    outputXmlTemplate:
`<alert>
  <id>{{uuid}}</id>
  <namespace>{{namespace}}</namespace>
  <topic>{{topic}}</topic>
  <title>{{$.title}}</title>
  <level>{{$.level}}</level>
  <message>{{$.message}}</message>
  {{$.buyer}}
  <traceId>{{traceId}}</traceId>
  <receivedAt>{{receivedAt}}</receivedAt>
</alert>`,
    responseCheck: {
      enabled: false,
      bodyType: 'AUTO',
      successPath: '$.errcode',
      successValue: '0',
      messagePath: '$.errmsg',
      operator: 'EQ'
    },
    transform: {
      enabled: true,
      mappings: [
        { from: '$.title',   to: 'title',   type: 'string', required: true },
        { from: '$.level',   to: 'level',   type: 'string', defaultValue: 'info' },
        { from: '$.message', to: 'message', type: 'string' }
      ]
    }
  }
}

// For non-webhook channels: {{$.path}} inline JSONPath is the preferred form.
// Old {{#jp}}...{{/jp}} syntax still works in the Mustache renderer.

function applyWebhookPreset() {
  const preset = TEMPLATE_PRESETS.WEBHOOK
  templates.value.WEBHOOK.outputFormat = templates.value.WEBHOOK.outputFormat ?? 'JSON'
  templates.value.WEBHOOK.outputTemplate = JSON.parse(JSON.stringify(preset.outputTemplate))
  templates.value.WEBHOOK.outputXmlTemplate = preset.outputXmlTemplate
  templates.value.WEBHOOK.responseCheck = JSON.parse(JSON.stringify(preset.responseCheck))
  templates.value.WEBHOOK.transform = JSON.parse(JSON.stringify(preset.transform))
  webhookOutputTplError.value = null
  syncWebhookTextFromTpl()
}

function applyTemplatePreset() {
  const preset = JSON.parse(JSON.stringify(TEMPLATE_PRESETS[channelTab.value]))
  templates.value[channelTab.value] = preset
}

function ensureTransform(): Transform {
  const tpl = templates.value[channelTab.value]
  if (!tpl.transform) {
    tpl.transform = { enabled: false, mappings: [] }
    templates.value = { ...templates.value }
  }
  return tpl.transform
}
function addMapping() {
  const tr = ensureTransform()
  tr.mappings = [...tr.mappings, { from: '$.', to: '', type: 'string', required: false }]
}
function removeMapping(i: number) {
  const tr = ensureTransform()
  const next = [...tr.mappings]
  next.splice(i, 1)
  tr.mappings = next
}

// Per-channel dry-run
const sampleInput = ref('{\n  "title": "服务异常",\n  "level": "error",\n  "message": "数据库连接超时",\n  "buyer": {\n    "name": "张三"\n  }\n}')
const dryRunResult = ref<any>(null)

async function runDryRun() {
  let parsed: any
  try { parsed = JSON.parse(sampleInput.value) }
  catch { return ElMessage.error('示例报文不是合法 JSON') }
  // save current channel template first so backend dryRun sees latest
  await saveTemplates()
  dryRunResult.value = await post(
    `/topics/${topic.value!.id}/template/dry-run?channel=${channelTab.value}`, parsed)
}

// ============ WEBHOOK output template helpers ============
// Use a separate ref for the textarea so typing isn't blocked by JSON parse failures.
const webhookOutputText = ref('')
const webhookOutputTplError = ref<string | null>(null)

/** Sync the text ref from the parsed template object */
function syncWebhookTextFromTpl() {
  if ((templates.value.WEBHOOK.outputFormat ?? 'JSON') === 'XML') {
    webhookOutputText.value = templates.value.WEBHOOK.outputXmlTemplate ?? ''
  } else {
    const tpl = templates.value.WEBHOOK.outputTemplate
    webhookOutputText.value = tpl ? JSON.stringify(tpl, null, 2) : ''
  }
  webhookOutputTplError.value = null
}

/** Parse the text ref back into the template object (call on blur / save) */
function syncWebhookTplFromText() {
  webhookOutputTplError.value = null
  if ((templates.value.WEBHOOK.outputFormat ?? 'JSON') === 'XML') {
    templates.value.WEBHOOK.outputXmlTemplate = webhookOutputText.value
    return
  }
  try {
    const parsed = JSON.parse(webhookOutputText.value || '{}')
    templates.value.WEBHOOK.outputTemplate = parsed
  } catch (e: any) {
    webhookOutputTplError.value = 'JSON 格式错误：' + e.message
  }
}

/** Extract dotted paths from the output template for autocomplete / display */
const webhookFields = computed(() => {
  const tpl = templates.value.WEBHOOK.outputTemplate
  if (!tpl || typeof tpl !== 'object') return []
  const paths: { path: string; type: string }[] = []
  function walk(node: any, prefix: string) {
    if (node === null || typeof node !== 'object') return
    if (Array.isArray(node)) return
    for (const [k, v] of Object.entries(node)) {
      const path = prefix ? `${prefix}.${k}` : k
      if (typeof v === 'string') {
        paths.push({ path, type: extractVarType(String(v)) })
      } else if (typeof v === 'number') {
        paths.push({ path, type: 'number' })
      } else if (typeof v === 'boolean') {
        paths.push({ path, type: 'boolean' })
      } else if (v && typeof v === 'object' && !Array.isArray(v)) {
        walk(v, path)
      }
    }
  }
  walk(tpl, '')
  return paths
})

function extractVarType(val: string): string {
  const m = val.match(/^\{\{(\w+)\}\}$/)
  if (!m) return 'string'
  return m[1] // e.g. "title", "uuid"
}

function queryWebhookFields(q: string, cb: (r: { value: string }[]) => void) {
  const needle = q.toLowerCase()
  const results = webhookFields.value
    .filter(p => !needle || p.path.toLowerCase().includes(needle))
    .map(p => ({ value: p.path }))
  cb(results)
}

// ============ Other state ============
const ipWhitelistText = ref('')
const subscription = ref<{ contactIds: string[] }>({ contactIds: [] })
const myContacts = ref<Contact[]>([])
const myApiKeys = ref<ApiKeyView[]>([])
const subscriptionQuery = ref('')
const subscriptionTreeRef = ref<any>()

const subscriptionTreeData = computed(() => {
  return CHANNELS
    .map(ch => {
      const children = myContacts.value
        .filter(c => c.type === ch.value)
        .map(c => ({
          id: c.id,
          label: `${c.label || '(未命名)'} — ${c.endpoint}`,
          type: c.type,
          endpoint: c.endpoint,
          enabled: c.enabled,
          isTarget: true
        }))
      return {
        id: `type:${ch.value}`,
        label: `${ch.label}（${children.length}）`,
        type: ch.value,
        isTarget: false,
        children
      }
    })
    .filter(group => group.children.length > 0)
})

function filterSubscriptionNode(value: string, data: any) {
  const needle = value.trim().toLowerCase()
  if (!needle) return true
  return String(data.label ?? '').toLowerCase().includes(needle)
      || String(data.type ?? '').toLowerCase().includes(needle)
      || String(data.endpoint ?? '').toLowerCase().includes(needle)
}

function onSubscriptionCheck() {
  subscription.value.contactIds = subscriptionTreeRef.value?.getCheckedKeys(true) ?? []
}

watch(subscriptionQuery, value => subscriptionTreeRef.value?.filter(value))
watch(tabName, async value => {
  if (value === 'subscribe') {
    await nextTick()
    subscriptionTreeRef.value?.setCheckedKeys(subscription.value.contactIds ?? [])
    subscriptionTreeRef.value?.filter(subscriptionQuery.value)
  }
})

async function loadTopic() {
  if (isNew.value) return
  topic.value = await get<Topic>(`/topics/${route.params.id}`)
  originalTopicName.value = topic.value.name
  try {
    const settings = await get<Settings>('/topics/settings')
    topicDefaultLimit.value = settings.perTopicPerMinute ?? 60
  } catch { topicDefaultLimit.value = 60 }
  schemaJson.value = JSON.stringify(topic.value.inboundFormat ?? {}, null, 2)
  syncSchemaJsonToVisual()

  // Hydrate per-channel templates with backend values.
  const tt = topic.value.templates ?? {}
  templates.value = {
    EMAIL:    tt.EMAIL    ?? {},
    DINGTALK: tt.DINGTALK ?? {},
    FEISHU:   tt.FEISHU   ?? {},
    WECOM:    tt.WECOM    ?? {},
    WEBHOOK: {
      outputFormat: tt.WEBHOOK?.outputFormat ?? 'JSON',
      outputTemplate: tt.WEBHOOK?.outputTemplate,
      outputXmlTemplate: tt.WEBHOOK?.outputXmlTemplate,
      responseCheck: tt.WEBHOOK?.responseCheck ?? { enabled: false, bodyType: 'AUTO', successPath: '$.errcode', successValue: '0', messagePath: '$.errmsg', operator: 'EQ' },
      transform: tt.WEBHOOK?.transform ?? { enabled: false, mappings: [] }
    }
  }
  syncWebhookTextFromTpl()

  topic.value.auth.rateLimit = topic.value.auth.rateLimit ?? {}
  ipWhitelistText.value = (topic.value.auth.ipWhitelist ?? []).join('\n')
  subscription.value = await get(`/topics/${topic.value.id}/subscription`)
  myContacts.value = await get<Contact[]>('/contacts')
  await nextTick()
  subscriptionTreeRef.value?.setCheckedKeys(subscription.value.contactIds ?? [])
  myApiKeys.value = await get<ApiKeyView[]>('/apikeys/covering', { params: { topicId: topic.value.id } })
}
onMounted(loadTopic)
watch(() => route.params.id, loadTopic)

// ============ Save handlers ============
async function createTopic() {
  const namespaceId = route.query.namespaceId as string
  if (!namespaceId) return ElMessage.error('缺少命名空间')
  let inbound: any
  try { inbound = JSON.parse(draftCreate.inboundFormat) }
  catch { return ElMessage.error('inboundFormat 不是合法 JSON') }
  const ips = draftCreate.ipWhitelist.split(/\n+/).map(s => s.trim()).filter(Boolean)
  const created = await post<Topic>(`/topics?namespaceId=${namespaceId}`, {
    name: draftCreate.name,
    description: draftCreate.description,
    sync: draftCreate.sync,
    syncTimeout: null,
    authMode: 'API_KEY',
    keyLocation: draftCreate.keyLocation,
    ipWhitelist: ips,
    inboundFormat: inbound
  })
  ElMessage.success('已创建（DRAFT）')
  router.replace({ name: 'topic-detail', params: { id: created.id } })
}

async function saveBasic() {
  if (!topic.value) return
  const nameChanged = topic.value.name !== originalTopicName.value
  if (nameChanged) {
    await ElMessageBox.confirm(
      'Topic 名称会影响 Webhook URL，且仅草稿状态可修改。确认保存？',
      '修改 Topic 名称',
      { type: 'warning' }
    )
  }
  const updated = await patch<Topic>(`/topics/${topic.value.id}`, {
    name: topic.value.name,
    description: topic.value.description,
    sync: topic.value.sync ?? false,
    syncTimeout: topic.value.syncTimeout ?? null
  })
  topic.value = updated
  originalTopicName.value = updated.name
  ElMessage.success('已保存')
}

async function saveSchema() {
  if (schemaMode.value === 'visual') syncVisualToSchemaJson()
  let parsed: any
  try { parsed = JSON.parse(schemaJson.value) } catch { return ElMessage.error('JSON 格式错误') }
  const updated = await patch<Topic>(`/topics/${topic.value!.id}`, { inboundFormat: parsed })
  topic.value = updated
  ElMessage.success('已保存')
}

async function saveTemplates() {
  syncWebhookTplFromText()  // ensure textarea → outputTemplate before saving
  const cleaned: Record<string, ChannelTemplate> = {}
  for (const [k, v] of Object.entries(templates.value)) {
    const subject = v.subject?.trim()
    const body = v.body?.trim()
    const hasOutputTpl = v.outputTemplate && Object.keys(v.outputTemplate).length > 0 || !!v.outputXmlTemplate?.trim()
    const transformActive = !!v.transform && (v.transform.enabled || (v.transform.mappings?.length ?? 0) > 0)
    const responseCheckActive = !!v.responseCheck && (v.responseCheck.enabled || !!v.responseCheck.successPath)
    if (subject || body || hasOutputTpl || transformActive || responseCheckActive) cleaned[k] = v
  }
  const updated = await patch<Topic>(`/topics/${topic.value!.id}`, { templates: cleaned })
  topic.value = updated
}

async function saveTemplatesAndNotify() {
  await saveTemplates()
  ElMessage.success('已保存')
}

async function saveAuth() {
  const ips = ipWhitelistText.value.split(/\n+/).map(s => s.trim()).filter(Boolean)
  const updated = await patch<Topic>(`/topics/${topic.value!.id}`, {
    auth: { mode: 'API_KEY', keyLocation: topic.value!.auth.keyLocation ?? 'HEADER', ipWhitelist: ips, rateLimit: topic.value!.auth.rateLimit ?? null }
  })
  topic.value = updated
  ElMessage.success('已保存')
}

async function saveSubscription() {
  onSubscriptionCheck()
  await put(`/topics/${topic.value!.id}/subscription`, subscription.value.contactIds)
  ElMessage.success('订阅已更新')
}

async function publish() {
  topic.value = await post<Topic>(`/topics/${topic.value!.id}/publish`)
  ElMessage.success('已发布')
}
async function disable() { topic.value = await post<Topic>(`/topics/${topic.value!.id}/disable`) }
async function enable()  { topic.value = await post<Topic>(`/topics/${topic.value!.id}/enable`) }
async function removeTopic() {
  await ElMessageBox.confirm('确定删除该 Topic？仅 DRAFT/DISABLED 可删除。', { type: 'warning' })
  await del(`/topics/${topic.value!.id}`)
  router.replace({ name: 'namespaces' })
}

const curlExample = computed(() => {
  if (!topic.value) return ''
  const baseUrl = `${location.origin}/api/webhook/${topic.value.namespaceName}/${topic.value.name}`
  const url = (topic.value.auth.keyLocation ?? 'HEADER') === 'QUERY' ? `${baseUrl}?key=<ApiKey>` : baseUrl
  const auth = (topic.value.auth.keyLocation ?? 'HEADER') === 'HEADER'
    ? '-H "Authorization: Bearer <ApiKey>" \\\n     '
    : ''
  return `curl -X POST "${url}" \\\n     ${auth}-H "Content-Type: application/json" \\\n     -d '${sampleInput.value.replace(/\n/g, '')}'`
})

const SAMPLE_VAR = '{{name}}'
const SAMPLE_JP = '{{$.user.name}}'
function pillText(name: string) { return `{{${name}}}` }

const varDialog = ref(false)
const availableVars = ref<{ group: string; name: string; desc: string }[]>([])

/** Group variable entries by their group field */
const groupedVars = computed(() => {
  const groups: { label: string; items: typeof availableVars.value }[] = []
  for (const v of availableVars.value) {
    let g = groups.find(g => g.label === v.group)
    if (!g) {
      g = { label: v.group, items: [] }
      groups.push(g)
    }
    g.items.push(v)
  }
  return groups
})

async function loadVars() {
  try { availableVars.value = await get('/template-vars') }
  catch { availableVars.value = [] }
}
function openVarDialog() { loadVars(); varDialog.value = true }

const SUBSCRIBED_CHANNELS = computed(() => {
  const types = new Set<ChannelType>()
  for (const cid of subscription.value.contactIds) {
    const c = myContacts.value.find(x => x.id === cid)
    if (c) types.add(c.type)
  }
  return Array.from(types)
})
</script>

<template>
  <!-- ============ NEW TOPIC ============ -->
  <div v-if="isNew">
    <h2 class="page-h">新建 Topic</h2>
    <el-card class="block">
      <el-form label-width="120px">
        <el-form-item label="Topic 名称" required>
          <el-input v-model="draftCreate.name" placeholder="3-32 字符，字母开头" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="draftCreate.description" />
        </el-form-item>
        <el-form-item label="投递模式">
          <el-radio-group v-model="draftCreate.sync">
            <el-radio-button :label="false">异步（默认）</el-radio-button>
            <el-radio-button :label="true">同步</el-radio-button>
          </el-radio-group>
          <div class="muted">同步模式下，调用方会等待通知投递完成后再返回结果。</div>
        </el-form-item>
        <el-form-item label="ApiKey 位置">
          <el-radio-group v-model="draftCreate.keyLocation">
            <el-radio-button label="HEADER">请求头 Authorization</el-radio-button>
            <el-radio-button label="QUERY">URL 参数 key</el-radio-button>
          </el-radio-group>
          <div class="muted">URL 参数模式形如 <code>?key=xxxxx</code>，更容易出现在代理日志中，推荐优先使用请求头。</div>
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input v-model="draftCreate.ipWhitelist" type="textarea" :rows="3"
                    placeholder="每行一条，CIDR 格式：例如 10.0.0.0/8" />
        </el-form-item>
        <el-form-item label="报文格式">
          <el-input v-model="draftCreate.inboundFormat" type="textarea" :rows="10" />
          <div class="muted">先用默认值即可，创建后可在「报文格式」Tab 中可视化编辑（含嵌套）。</div>
        </el-form-item>
        <el-button type="primary" @click="createTopic">创建（DRAFT）</el-button>
      </el-form>
    </el-card>
  </div>

  <!-- ============ EXISTING TOPIC ============ -->
  <div v-else-if="topic">
    <div class="header">
      <div>
        <h2 class="page-h">{{ topic.namespaceName }} / {{ topic.name }}</h2>
        <el-tag :type="topic.status === 'PUBLISHED' ? 'success' : topic.status === 'DISABLED' ? 'danger' : 'info'">
          {{ topic.status }}
        </el-tag>
      </div>
      <div>
        <el-button v-if="topic.status === 'DRAFT'" type="primary" @click="publish">发布</el-button>
        <el-button v-if="topic.status === 'PUBLISHED'" @click="disable">禁用</el-button>
        <el-button v-if="topic.status === 'DISABLED'" type="primary" @click="enable">恢复</el-button>
        <el-button v-if="topic.status !== 'PUBLISHED'" type="danger" @click="removeTopic">删除</el-button>
      </div>
    </div>

    <el-tabs v-model="tabName" class="tabs">
      <!-- ===== Basic ===== -->
      <el-tab-pane label="基础信息" name="basic">
        <el-form label-width="120px" class="form">
          <el-form-item label="ID"><el-input :model-value="topic.id" readonly /></el-form-item>
          <el-form-item label="Topic 名称" required>
            <el-input v-model="topic.name" :disabled="topic.status !== 'DRAFT'"
                      placeholder="3-32 字符，字母开头，可含数字 / _ / -" />
            <div class="muted">名称影响 Webhook URL；发布或禁用后不可修改。</div>
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="topic.description" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="投递模式">
            <el-radio-group v-model="topic.sync">
              <el-radio-button :value="false">异步</el-radio-button>
              <el-radio-button :value="true">同步</el-radio-button>
            </el-radio-group>
            <div class="muted">同步模式下，调用方会等待投递完成再返回。</div>
          </el-form-item>
          <el-form-item label="同步超时">
            <el-input-number v-model="topic.syncTimeout" :min="0" :max="300" size="small" placeholder="留空使用全局配置" />
            <span class="muted" style="margin-left: 8px">秒（0 = 不限制，留空 = 使用系统设置）</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveBasic">保存基础信息</el-button>
          </el-form-item>
          <el-form-item label="创建时间">
            <span class="muted">{{ formatDateTime(topic.createdAt) }}</span>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ===== Schema ===== -->
      <el-tab-pane label="报文格式" name="schema">
        <el-alert type="info" :closable="false" class="info-alert">
          <strong>什么是报文格式？</strong>
          调用方 POST 报文必须满足的形状（基于 JSON Schema Draft 2020-12）。
          支持嵌套对象、数组、枚举值。校验不通过的请求会被 400 拒绝。
          <span class="muted-inline">Topic 已发布后此项不可改 —— 调用方契约稳定。</span>
        </el-alert>

        <div class="row-flex" style="justify-content: space-between">
          <div class="row-flex">
            <span class="muted">编辑模式：</span>
            <el-radio-group :model-value="schemaMode" size="small"
                            @change="(val: any) => onSchemaModeChange(val)">
              <el-radio-button value="visual">可视化（支持嵌套）</el-radio-button>
              <el-radio-button value="source">JSON 源码</el-radio-button>
            </el-radio-group>
          </div>
          <div class="row-flex" style="margin-top: 0">
            <span class="muted">从预设开始：</span>
            <el-button v-for="p in SCHEMA_PRESETS" :key="p.label" size="small"
                       @click="applySchemaPreset(p.sample)" :disabled="topic.status === 'PUBLISHED'">
              {{ p.label }}
            </el-button>
          </div>
        </div>

        <!-- Visual mode: recursive tree of fields -->
        <div v-if="schemaMode === 'visual'" style="margin-top: 12px">
          <el-alert v-if="schemaError" type="error" :closable="false"
                    :title="'当前 JSON 解析失败（请切到「JSON 源码」检查）：' + schemaError" />
          <template v-else>
            <div class="schema-header">
              <span style="width:120px">字段名</span>
              <span style="width:85px">类型</span>
              <span style="width:60px">必填</span>
              <span style="flex:1">描述</span>
              <span style="width:130px">枚举值</span>
              <span style="width:26px"></span>
              <span style="width:26px"></span>
            </div>
            <SchemaFieldRow
              v-for="(f, i) in visualFields"
              :key="i"
              v-model="visualFields[i]"
              :level="0"
              :disabled="topic.status === 'PUBLISHED'"
              @remove="visualFields.splice(i, 1)"
            />
            <div class="schema-actions">
              <el-button size="small" @click="addRootField" type="primary" link
                         :disabled="topic.status === 'PUBLISHED'">
                + 添加顶级字段
              </el-button>
              <span class="schema-hint">
                注意：只有将字段的「类型」设置为 <code>object</code> 或 <code>array</code> 后，
                行尾才会出现 [+] 按钮用于添加子字段或配置数组元素结构。
              </span>
            </div>
          </template>
        </div>

        <!-- Source mode -->
        <div v-else style="margin-top: 12px">
          <div class="muted">JSON Schema 源码（高级用法可写 $ref / oneOf / 复杂校验等）</div>
          <el-input v-model="schemaJson" type="textarea" :rows="18"
                    :disabled="topic.status === 'PUBLISHED'" />
        </div>

        <el-button type="primary" @click="saveSchema" style="margin-top: 12px"
                   :disabled="topic.status === 'PUBLISHED'">保存</el-button>
      </el-tab-pane>

      <!-- ===== Channel templates ===== -->
      <el-tab-pane label="通知模板" name="template">
        <el-alert type="info" :closable="false" class="info-alert">
          <strong>按通知目标类型隔离配置</strong>。订阅时根据目标类型自动匹配模板。
          非 Webhook 渠道用 Mustache 渲染，Webhook 渠道用 JSON 出站模板 + 映射表。
          所有渠道变量体系相同：<el-link type="primary" :underline="false" @click="openVarDialog">查看可用变量</el-link>。
          当前订阅渠道：
          <el-tag v-for="t in SUBSCRIBED_CHANNELS" :key="t" size="small" effect="plain"
                  style="margin-left: 4px">{{ t }}</el-tag>
          <span v-if="!SUBSCRIBED_CHANNELS.length" class="muted-inline"> 无订阅</span>
        </el-alert>

        <el-tabs v-model="channelTab" type="card">
          <el-tab-pane v-for="c in CHANNELS" :key="c.value" :name="c.value" :label="c.label">
            <div class="row-flex" style="margin-bottom: 12px">
              <el-button size="small" @click="c.hasOutputTemplate ? applyWebhookPreset() : applyTemplatePreset()">
                使用 {{ c.label }} 预设
              </el-button>
              <span class="muted-inline">仅当订阅含 {{ c.label }} 类型目标时此模板生效</span>
            </div>

            <!-- Subject + body for non-webhook channels -->
            <template v-if="c.hasSubject || c.hasBody">
              <el-row :gutter="12">
                <el-col :span="14">
                  <div v-if="c.hasSubject">
                    <div class="muted">主题</div>
                    <el-input v-model="templates[c.value].subject"
                              :placeholder="c.value === 'EMAIL' ? '邮件主题' : '消息标题'" />
                  </div>
                  <div v-if="c.hasBody" style="margin-top: 12px">
                    <div class="muted">正文（{{ c.label === 'EMAIL' ? 'HTML' : 'Markdown' }}）</div>
                    <el-input v-model="templates[c.value].body" type="textarea" :rows="14" />
                  </div>
                </el-col>
                <el-col :span="10">
                  <div class="var-list">
                    <code v-for="v in ['namespace', 'topic', 'traceId', 'receivedAt', 'rawJson']" :key="v"
                          class="var-pill"
                          @click="templates[c.value].body = (templates[c.value].body ?? '') + pillText(v)">
                      {{ pillText(v) }}
                    </code>
                    <code class="var-pill"
                          @click="templates[c.value].body = (templates[c.value].body ?? '') + '{{' + '$.path' + '}}'">
                      <span v-text="'{{' + '$.path' + '}}'" />
                    </code>
                    <el-link type="primary" :underline="false" size="small"
                             style="margin-left: 8px"
                             @click="openVarDialog">查看所有变量</el-link>
                  </div>
                </el-col>
              </el-row>
            </template>

            <!-- Webhook output template + mappings -->
            <template v-if="c.hasOutputTemplate">
              <el-alert type="info" :closable="false" style="margin-bottom: 12px">
                <strong>出站报文模板</strong>：可选择 JSON 或 XML。XML 模式下，<code v-text="'{{$.path}}'" />
                指向对象/数组时会先转换为 XML 节点片段，再插入目标 XML 对应位置。
              </el-alert>

              <div class="row-flex" style="margin-bottom: 12px">
                <span>启用模板渲染</span>
                <el-switch v-model="templates.WEBHOOK.transform.enabled" />
                <span class="muted-inline">目标格式</span>
                <el-radio-group v-model="templates.WEBHOOK.outputFormat" size="small" @change="syncWebhookTextFromTpl">
                  <el-radio-button :value="'JSON'">JSON</el-radio-button>
                  <el-radio-button :value="'XML'">XML</el-radio-button>
                </el-radio-group>
              </div>

              <el-row :gutter="12">
                <el-col :span="(templates.WEBHOOK.outputFormat || 'JSON') === 'JSON' ? 14 : 24">
                  <div class="muted">目标报文格式（{{ templates.WEBHOOK.outputFormat || 'JSON' }}，支持 <code v-text="'{{变量}}'" /> 占位符）</div>
                  <el-input v-model="webhookOutputText" type="textarea" :rows="12"
                            @blur="syncWebhookTplFromText" />
                  <div v-if="webhookOutputTplError" class="error-hint">
                    {{ webhookOutputTplError }}
                  </div>
                  <el-button size="small" style="margin-top: 4px"
                             @click="applyWebhookPreset(); syncWebhookTextFromTpl()">使用预设模板</el-button>
                </el-col>
                <el-col v-if="(templates.WEBHOOK.outputFormat || 'JSON') === 'JSON'" :span="10">
                  <div class="muted">模板字段路径（自动提取，用于映射 To 列）</div>
                  <div class="tpl-structure">
                    <template v-if="webhookFields.length">
                      <div v-for="p in webhookFields" :key="p.path" class="tpl-field">
                        <code class="tpl-path">{{ p.path }}</code>
                        <span class="tpl-type muted-inline">{{ p.type }}</span>
                      </div>
                    </template>
                    <span v-else class="muted-inline">编辑上方 JSON 后自动提取路径</span>
                  </div>
                </el-col>
              </el-row>

              <h4 class="sub-h">字段映射（入站 → 出站模板）</h4>
              <el-table :data="templates.WEBHOOK.transform.mappings" size="small" border style="margin-top: 8px">
                <el-table-column label="From（JSONPath）" width="220">
                  <template #default="{ row }">
                    <el-input v-model="row.from" placeholder="$.title" size="small" />
                  </template>
                </el-table-column>
                <el-table-column label="To（模板字段路径）" width="200">
                  <template #default="{ row }">
                    <el-autocomplete
                      v-model="row.to"
                      :fetch-suggestions="queryWebhookFields"
                      placeholder="title 或 user.name"
                      size="small"
                      style="width: 100%"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="类型" width="140">
                  <template #default="{ row }">
                    <el-select v-model="row.type" size="small">
                      <el-option label="string" value="string" />
                      <el-option label="number" value="number" />
                      <el-option label="boolean" value="boolean" />
                      <el-option label="json (原样)" value="json" />
                      <el-option label="array<string>" value="array<string>" />
                      <el-option label="array<number>" value="array<number>" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="必填" width="70">
                  <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
                </el-table-column>
                <el-table-column label="缺省值">
                  <template #default="{ row }">
                    <el-input v-model="row.defaultValue" size="small" placeholder="From 取空时使用" />
                  </template>
                </el-table-column>
                <el-table-column width="60">
                  <template #default="{ $index }">
                    <el-button link type="danger" size="small" @click="removeMapping($index)">×</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button size="small" @click="addMapping" style="margin-top: 8px">+ 添加映射</el-button>

              <h4 class="sub-h">响应断言</h4>
              <el-alert type="info" :closable="false" style="margin-bottom: 12px">
                当 HTTP 状态码为 2xx 但响应体字段不符合断言时，本次 Webhook 通知会被视为失败并进入重试队列。
              </el-alert>
              <el-form label-width="130px" class="form">
                <el-form-item label="启用响应断言">
                  <el-switch v-model="templates.WEBHOOK.responseCheck.enabled" />
                </el-form-item>
                <template v-if="templates.WEBHOOK.responseCheck.enabled">
                  <el-form-item label="响应格式">
                    <el-radio-group v-model="templates.WEBHOOK.responseCheck.bodyType">
                      <el-radio-button :value="'AUTO'">自动识别</el-radio-button>
                      <el-radio-button :value="'JSON'">JSON</el-radio-button>
                      <el-radio-button :value="'XML'">XML</el-radio-button>
                    </el-radio-group>
                  </el-form-item>
                  <el-form-item label="成功字段路径">
                    <el-input v-model="templates.WEBHOOK.responseCheck.successPath" placeholder="JSON: $.errcode / XML: /xml/errcode" />
                  </el-form-item>
                  <el-form-item label="判断方式">
                    <el-select v-model="templates.WEBHOOK.responseCheck.operator" style="width: 180px">
                      <el-option label="等于" value="EQ" />
                      <el-option label="不等于" value="NE" />
                      <el-option label="包含" value="CONTAINS" />
                      <el-option label="正则匹配" value="REGEX" />
                      <el-option label="大于" value="GT" />
                      <el-option label="小于" value="LT" />
                      <el-option label="存在" value="EXISTS" />
                    </el-select>
                  </el-form-item>
                  <el-form-item v-if="templates.WEBHOOK.responseCheck.operator !== 'EXISTS'" label="期望值">
                    <el-input v-model="templates.WEBHOOK.responseCheck.successValue" placeholder="例如 0" />
                  </el-form-item>
                  <el-form-item label="失败提示路径">
                    <el-input v-model="templates.WEBHOOK.responseCheck.messagePath" placeholder="JSON: $.errmsg / XML: /xml/errmsg" />
                  </el-form-item>
                </template>
              </el-form>
            </template>
          </el-tab-pane>
        </el-tabs>

        <!-- Shared dry-run -->
        <h4 class="sub-h">试运行</h4>
        <el-row :gutter="12">
          <el-col :span="12">
            <div class="muted">示例入站报文</div>
            <el-input v-model="sampleInput" type="textarea" :rows="10" />
            <div style="margin-top: 8px">
              <el-button type="primary" @click="runDryRun">运行（{{ currentChannel.label }}）</el-button>
              <el-button @click="saveTemplatesAndNotify">保存全部模板</el-button>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="muted">输出</div>
            <el-alert v-if="dryRunResult?.schemaOk === false" type="error" :closable="false"
                      :title="'Schema 校验失败：' + (dryRunResult.schemaError ?? '')" />
            <template v-else-if="dryRunResult && currentChannel.hasOutputTemplate">
              <template v-if="dryRunResult.outputFormat === 'XML'">
                <pre class="result">{{ dryRunResult.outputXml || '(空)' }}</pre>
              </template>
              <template v-else>
                <pre class="result">{{ JSON.stringify(dryRunResult.output, null, 2) }}</pre>
              </template>
              <template v-if="dryRunResult.traces?.length">
                <div class="muted" style="margin-top: 8px">每条映射命中情况</div>
                <el-table :data="dryRunResult.traces" size="small" border>
                  <el-table-column prop="from" label="from" />
                  <el-table-column prop="to" label="to" />
                  <el-table-column label="结果" width="80">
                    <template #default="{ row }">
                      <el-tag size="small" :type="row.ok ? 'success' : 'danger'">
                        {{ row.ok ? 'OK' : '×' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="message" label="说明" />
                </el-table>
              </template>
            </template>
            <template v-else-if="dryRunResult">
              <div class="muted">主题</div>
              <div class="preview-subject">{{ dryRunResult.subject || '(空)' }}</div>
              <div class="muted" style="margin-top: 8px">正文</div>
              <div v-if="channelTab === 'EMAIL'" class="preview-body" v-html="dryRunResult.body" />
              <pre v-else class="result">{{ dryRunResult.body }}</pre>
            </template>
            <pre v-else class="result">点击「运行」查看结果</pre>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- ===== Subscribe ===== -->
      <el-tab-pane label="订阅" name="subscribe">
        <el-alert type="info" :closable="false" class="info-alert">
          勾选要接收此 Topic 通知的目标。各目标根据自己的「类型」自动从「通知模板」Tab 取对应渠道的模板。
        </el-alert>
        <div class="subscription-toolbar">
          <el-input v-model="subscriptionQuery" clearable placeholder="搜索类型 / 名称 / 地址" style="width: 320px" />
          <span class="muted-inline">已选择 {{ subscription.contactIds.length }} 个目标</span>
        </div>
        <el-tree
          ref="subscriptionTreeRef"
          class="subscription-tree"
          :data="subscriptionTreeData"
          node-key="id"
          show-checkbox
          default-expand-all
          :filter-node-method="filterSubscriptionNode"
          @check="onSubscriptionCheck"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <template v-if="data.isTarget">
                <el-tag size="small" effect="plain" style="margin-right: 6px">{{ data.type }}</el-tag>
                <span>{{ data.label }}</span>
                <el-tag v-if="!data.enabled" size="small" type="danger" style="margin-left: 6px">已停用</el-tag>
              </template>
              <template v-else>
                <strong>{{ data.label }}</strong>
              </template>
            </span>
          </template>
        </el-tree>
        <el-empty v-if="!myContacts.length" description="尚未添加通知目标" />
        <el-button type="primary" @click="saveSubscription" style="margin-top: 12px">保存</el-button>
      </el-tab-pane>

      <!-- ===== Security & Access ===== -->
      <el-tab-pane label="安全与接入" name="security">
        <el-form label-width="120px" class="form">
          <el-form-item label="ApiKey 位置">
            <el-radio-group v-model="topic.auth.keyLocation">
              <el-radio-button label="HEADER">请求头 Authorization</el-radio-button>
              <el-radio-button label="QUERY">URL 参数 key</el-radio-button>
            </el-radio-group>
            <div class="muted">URL 参数模式形如 <code>?key=xxxxx</code>，更容易出现在代理日志中，推荐优先使用请求头。</div>
          </el-form-item>
          <el-form-item label="IP 白名单">
            <el-input v-model="ipWhitelistText" type="textarea" :rows="4"
                      placeholder="每行一条，CIDR 格式：例如 10.0.0.0/8" />
          </el-form-item>
          <el-form-item label="Topic 限流">
            <el-input-number v-model="topic.auth.rateLimit.perMinute" :min="1" :max="99999" size="small" placeholder="默认" />
            <div class="muted">限制该 Topic 每分钟调用次数；留空使用系统默认 {{ topicDefaultLimit }} 次/分钟。</div>
          </el-form-item>
          <el-button type="primary" @click="saveAuth">保存安全设置</el-button>
        </el-form>

        <el-divider />
        <h4 class="sub-h">关联 ApiKey（仅本 Topic 可用）</h4>
        <el-table :data="myApiKeys" empty-text="尚无可用 ApiKey">
          <el-table-column prop="name" label="名称" />
          <el-table-column label="前缀" width="160">
            <template #default="{ row }"><code>{{ row.prefix }}••••</code></template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column label="失效时间">
            <template #default="{ row }">
              <span v-if="row.validUntil">{{ formatDateTime(row.validUntil) }}</span>
              <span v-else class="muted-inline">永久有效</span>
            </template>
          </el-table-column>
        </el-table>

        <el-divider />
        <h4 class="sub-h">cURL 示例</h4>
        <pre class="result">{{ curlExample }}</pre>
      </el-tab-pane>
    </el-tabs>
  </div>

  <!-- Variable reference dialog -->
  <el-dialog v-model="varDialog" title="可用模板变量" width="640px">
    <template v-for="g in groupedVars" :key="g.label">
      <h4 class="var-group-label">{{ g.label }}</h4>
      <el-table :data="g.items" size="small" border :show-header="false" style="margin-bottom: 12px">
        <el-table-column prop="name" label="变量" width="220">
          <template #default="{ row }">
            <code class="mono"><span v-text="'{{' + row.name + '}}'" /></code>
          </template>
        </el-table-column>
        <el-table-column prop="desc" label="说明" />
      </el-table>
    </template>
    <div class="var-dialog-hint">
      函数同时兼容 Mustache 语法，如 <code v-text="'{{#func}}body{{/func}}'" />
    </div>
    <template #footer>
      <el-button @click="varDialog = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.var-group-label {
  color: var(--la-fg);
  font-size: 13px;
  font-weight: 600;
  margin: 0 0 6px;
  padding-left: 8px;
  border-left: 3px solid var(--la-accent);
}
.var-dialog-hint {
  background: var(--la-accent-soft);
  border: 1px solid rgba(61, 124, 255, 0.2);
  border-radius: 6px;
  padding: 8px 12px;
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--la-fg);
  text-align: center;
}
.var-dialog-hint code {
  background: var(--la-bg-elevated);
  padding: 1px 6px;
  border-radius: 3px;
  color: var(--la-accent);
}

.page-h { color: var(--la-fg); margin: 0 0 6px; display: inline-block; margin-right: 12px; }
.header { display:flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.tabs { background: var(--la-bg-elevated); padding: 16px; border: 1px solid var(--la-border); border-radius: 8px; }
.block { background: var(--la-bg-elevated); border: 1px solid var(--la-border); }
.form { max-width: 720px; }
.muted { color: var(--la-fg-muted); margin: 0 0 6px; font-size: 13px; }
.muted-inline { color: var(--la-fg-muted); }
.row-flex { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 12px; }
.info-alert { margin-bottom: 16px; }
.info-alert :deep(.el-alert__content) { line-height: 1.7; }
.result { background: var(--la-bg); padding: 12px; border-radius: 6px; color: var(--la-fg); overflow: auto;
          font-size: 12px; border: 1px solid var(--la-border); margin: 0; white-space: pre-wrap; word-break: break-word; }
.sub-h { color: var(--la-fg); margin: 16px 0 8px; font-size: 14px; }
.var-list { display: flex; flex-wrap: wrap; gap: 6px; }
.var-pill { cursor: pointer; user-select: none; padding: 2px 8px; }
.var-pill:hover { background: var(--la-accent); color: #fff; }
.preview-body { background: var(--la-bg); border: 1px solid var(--la-border); border-radius: 6px;
                padding: 12px; color: var(--la-fg); font-size: 13px; line-height: 1.6; }
.preview-subject { font-weight: 600; color: var(--la-fg); padding: 6px 12px;
                   background: var(--la-bg); border: 1px solid var(--la-border); border-radius: 6px; }
.schema-header { display: grid; grid-template-columns: 120px 85px 60px 1fr 130px 26px 26px; gap: 6px;
                 padding: 6px 0 4px; color: var(--la-fg-muted); font-size: 12px;
                 border-bottom: 1px solid var(--la-border); margin-bottom: 4px; }
.schema-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.schema-hint { color: var(--la-fg-muted); font-size: 12px; }
.schema-hint code { background: var(--la-accent-soft); color: var(--la-accent);
                    padding: 1px 6px; border-radius: 4px; font-size: 12px; }
.tpl-structure { background: var(--la-bg); border: 1px solid var(--la-border); border-radius: 6px;
                 padding: 8px 12px; max-height: 200px; overflow-y: auto; }
.tpl-field { display: flex; gap: 8px; align-items: center; padding: 2px 0; }
.tpl-path { font-family: ui-monospace, monospace; font-size: 12px; color: var(--la-accent); }
.tpl-type { font-size: 11px; }
.subscription-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.subscription-tree { background: var(--la-bg); border: 1px solid var(--la-border); border-radius: 6px; padding: 8px 4px; }
.tree-node { display: inline-flex; align-items: center; gap: 2px; min-width: 0; }
:deep(.el-tree-node__content) { height: 32px; }
:deep(.el-tree-node__label) { color: var(--la-fg); }
:deep(.el-tabs__item) { color: var(--la-fg-muted); }
:deep(.el-tabs__item.is-active) { color: var(--la-accent); }
</style>
