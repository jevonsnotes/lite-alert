<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheck, CircleClose, Folder, Lock, Promotion, Setting, Refresh
} from '@element-plus/icons-vue'
import { get, put, post, del } from '@/http'
import { formatDateTime } from '@/utils/datetime'

type Health = {
  status: string
  time: string
  dataDir: string
  dataDirWritable: boolean
  smtpConfigured: boolean
  smtpOverridden: boolean
  publicTopicEnabled: boolean
}

type MailConfigView = {
  host: string
  port: number
  username?: string
  hasPassword: boolean
  ssl: boolean
  fromAddress?: string
  fromName?: string
  updatedAt?: string
  updatedBy?: string
} | null

type Span = { value: number; unit: 'DAYS' | 'MONTHS' | 'YEARS' }
type Settings = {
  auditRetention: Span
  dashboardDefaultTrend: Span
}

const UNITS = [
  { label: '天',  value: 'DAYS' },
  { label: '月',  value: 'MONTHS' },
  { label: '年',  value: 'YEARS' }
]

const health = ref<Health | null>(null)
const mailWrap = ref<{ overridden: boolean; config: MailConfigView } | null>(null)
const settings = reactive<Settings>({
  auditRetention: { value: 90, unit: 'DAYS' },
  dashboardDefaultTrend: { value: 14, unit: 'DAYS' }
})
const settingsSaving = ref(false)

const form = reactive({
  host: '',
  port: 465,
  username: '',
  password: '',
  ssl: true,
  fromAddress: '',
  fromName: ''
})

const loading = ref(false)
const testing = ref(false)
const testEmail = ref('')

async function loadAll() {
  loading.value = true
  try {
    health.value = await get<Health>('/admin/health')
    mailWrap.value = await get('/admin/mail-config')
    if (mailWrap.value?.config) {
      const c = mailWrap.value.config
      form.host = c.host ?? ''
      form.port = c.port ?? 465
      form.username = c.username ?? ''
      form.password = ''         // never echoed; "" means keep existing
      form.ssl = c.ssl ?? true
      form.fromAddress = c.fromAddress ?? ''
      form.fromName = c.fromName ?? ''
    }
    const s = await get<Settings>('/admin/settings')
    settings.auditRetention = s.auditRetention
    settings.dashboardDefaultTrend = s.dashboardDefaultTrend
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

async function saveConfig() {
  if (!form.host) return ElMessage.warning('请填写 SMTP 服务器地址')
  loading.value = true
  try {
    mailWrap.value = await put('/admin/mail-config', {
      host: form.host,
      port: form.port,
      username: form.username || null,
      password: form.password,        // empty → backend keeps existing
      ssl: form.ssl,
      fromAddress: form.fromAddress || null,
      fromName: form.fromName || null
    })
    form.password = ''                // clear the input back to "keep" mode
    ElMessage.success('已保存并立即生效')
  } finally {
    loading.value = false
  }
}

async function resetConfig() {
  await ElMessageBox.confirm(
    '重置后将回退到 application.yml 中的 SMTP 配置（如未配置则停用邮件渠道）。继续？',
    '重置 SMTP', { type: 'warning' }
  )
  mailWrap.value = await del('/admin/mail-config')
  Object.assign(form, { host: '', port: 465, username: '', password: '', ssl: true, fromAddress: '', fromName: '' })
  ElMessage.success('已重置')
}

async function sendTest() {
  if (!testEmail.value) return ElMessage.warning('请填写收件人邮箱')
  testing.value = true
  try {
    const r = await post<any>('/admin/smtp-test', { to: testEmail.value })
    if (r.ok) {
      ElMessage.success('测试邮件已发送，请检查收件箱')
    } else {
      ElMessageBox.alert(r.error || '未知错误', '测试失败', { type: 'error' })
    }
  } finally {
    testing.value = false
  }
}

async function saveSettings() {
  settingsSaving.value = true
  try {
    const saved = await put<Settings>('/admin/settings', {
      auditRetention: { ...settings.auditRetention },
      dashboardDefaultTrend: { ...settings.dashboardDefaultTrend }
    })
    settings.auditRetention = saved.auditRetention
    settings.dashboardDefaultTrend = saved.dashboardDefaultTrend
    ElMessage.success('已保存')
  } finally {
    settingsSaving.value = false
  }
}
</script>

<template>
  <div>
    <h2 class="page-h">系统设置</h2>

    <!-- ============ STATUS ============ -->
    <h3 class="section-h">实例状态</h3>
    <el-row :gutter="12" class="status-row" v-if="health">
      <el-col :span="6">
        <div class="stat-card" :class="{ ok: health.status === 'UP' }">
          <div class="stat-icon"><el-icon :size="24"><Promotion /></el-icon></div>
          <div>
            <div class="stat-label">运行状态</div>
            <div class="stat-value">{{ health.status }}</div>
            <div class="stat-sub">{{ formatDateTime(health.time) }}</div>
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card" :class="{ ok: health.dataDirWritable, bad: !health.dataDirWritable }">
          <div class="stat-icon"><el-icon :size="24"><Folder /></el-icon></div>
          <div>
            <div class="stat-label">数据目录</div>
            <div class="stat-value">
              <el-icon v-if="health.dataDirWritable" color="#10b981"><CircleCheck /></el-icon>
              <el-icon v-else color="#ef4444"><CircleClose /></el-icon>
              {{ health.dataDirWritable ? '可写' : '只读' }}
            </div>
            <div class="stat-sub" :title="health.dataDir">{{ health.dataDir }}</div>
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card" :class="{ ok: health.smtpConfigured }">
          <div class="stat-icon"><el-icon :size="24"><Setting /></el-icon></div>
          <div>
            <div class="stat-label">SMTP</div>
            <div class="stat-value">
              <el-icon v-if="health.smtpConfigured" color="#10b981"><CircleCheck /></el-icon>
              <el-icon v-else color="#9aa0aa"><CircleClose /></el-icon>
              {{ health.smtpConfigured ? '已配置' : '未配置' }}
            </div>
            <div class="stat-sub">{{ health.smtpOverridden ? '使用界面配置' : '使用 yml 配置' }}</div>
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card" :class="{ warn: health.publicTopicEnabled }">
          <div class="stat-icon"><el-icon :size="24"><Lock /></el-icon></div>
          <div>
            <div class="stat-label">免认证 Topic</div>
            <div class="stat-value">
              {{ health.publicTopicEnabled ? '所有用户可建' : '仅 ADMIN' }}
            </div>
            <div class="stat-sub">webhook.allow-user-public-topic</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ RUNTIME SETTINGS ============ -->
    <h3 class="section-h">系统行为</h3>
    <el-card class="block">
      <el-form label-width="180px">
        <el-form-item label="审计日志保留">
          <el-input-number v-model="settings.auditRetention.value" :min="1" :max="3650" size="small" />
          <el-select v-model="settings.auditRetention.unit" size="small" style="width: 90px; margin-left: 8px">
            <el-option v-for="u in UNITS" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
          <div class="muted">超过保留期的 <code>audit/yyyy-MM-dd.log</code> 文件每天 03:17 自动删除。</div>
        </el-form-item>
        <el-form-item label="仪表盘默认时间窗口">
          <el-input-number v-model="settings.dashboardDefaultTrend.value" :min="1" :max="365" size="small" />
          <el-select v-model="settings.dashboardDefaultTrend.unit" size="small" style="width: 90px; margin-left: 8px">
            <el-option v-for="u in UNITS" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
          <div class="muted">仪表盘趋势图首次打开时使用此窗口，用户可在页面临时调整。</div>
        </el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="settingsSaving" @click="saveSettings">保存</el-button>
        </div>
      </el-form>
    </el-card>

    <!-- ============ SMTP CONFIG ============ -->
    <h3 class="section-h">SMTP 配置</h3>
    <el-card class="block">
      <template #header>
        <div class="card-header">
          <span>SMTP 服务器</span>
          <div class="header-actions">
            <el-tag v-if="mailWrap?.overridden" type="success" size="small">界面配置生效</el-tag>
            <el-tag v-else size="small">仅 application.yml</el-tag>
            <el-button v-if="mailWrap?.overridden" link type="warning" size="small"
                       @click="resetConfig">重置为 yml</el-button>
            <el-button link size="small" :icon="Refresh" @click="loadAll">刷新</el-button>
          </div>
        </div>
      </template>

      <el-form label-width="120px" :inline="false" v-loading="loading">
        <el-row :gutter="12">
          <el-col :span="14">
            <el-form-item label="服务器" required>
              <el-input v-model="form.host" placeholder="smtp.example.com" />
            </el-form-item>
          </el-col>
          <el-col :span="10">
            <el-form-item label="端口" required>
              <el-input-number v-model="form.port" :min="1" :max="65535" :step="1" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="协议">
          <el-radio-group v-model="form.ssl">
            <el-radio :value="true">SSL（端口 465）</el-radio>
            <el-radio :value="false">STARTTLS（端口 25 / 587）</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="例如 notice@example.com" autocomplete="username" />
        </el-form-item>

        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="mailWrap?.config?.hasPassword ? '已保存（留空表示不修改）' : '请输入密码'"
                    autocomplete="new-password" />
          <div class="muted">服务端使用 Jasypt 加密落盘，永远不返回明文。</div>
        </el-form-item>

        <el-divider>发件人（可选）</el-divider>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="发件邮箱">
              <el-input v-model="form.fromAddress" placeholder="默认使用上方用户名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="发件人名称">
              <el-input v-model="form.fromName" placeholder="例如 Lite-Alert 通知" />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-actions">
          <el-button type="primary" @click="saveConfig" :loading="loading">保存并立即生效</el-button>
          <span v-if="mailWrap?.config?.updatedAt" class="muted-inline">
            上次更新：{{ formatDateTime(mailWrap.config.updatedAt) }}
          </span>
        </div>
      </el-form>
    </el-card>

    <!-- ============ TEST ============ -->
    <h3 class="section-h">发送测试邮件</h3>
    <el-card class="block">
      <el-form :inline="true">
        <el-form-item label="收件人">
          <el-input v-model="testEmail" placeholder="your@example.com" style="width: 320px" />
        </el-form-item>
        <el-button type="primary" @click="sendTest" :loading="testing"
                   :disabled="!health?.smtpConfigured">发送测试邮件</el-button>
      </el-form>
      <div class="muted">
        使用当前生效的 SMTP 配置同步发送一封固定模板邮件。失败会显示具体的 SMTP 错误信息便于排查。
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-h { color: var(--la-fg); margin: 0 0 16px; }
.section-h { color: var(--la-fg); font-size: 15px; margin: 24px 0 12px; }

.status-row { margin-bottom: 8px; }
.stat-card {
  display: flex; align-items: center; gap: 14px;
  background: var(--la-bg-elevated);
  border: 1px solid var(--la-border);
  border-left: 3px solid var(--la-fg-muted);
  border-radius: 6px;
  padding: 14px 16px;
  height: 88px;
}
.stat-card.ok   { border-left-color: #10b981; }
.stat-card.warn { border-left-color: #f59e0b; }
.stat-card.bad  { border-left-color: #ef4444; }
.stat-icon { color: var(--la-fg-muted); display: flex; align-items: center; }
.stat-label { color: var(--la-fg-muted); font-size: 12px; }
.stat-value { color: var(--la-fg); font-size: 18px; font-weight: 600; margin-top: 2px;
              display: flex; align-items: center; gap: 4px; }
.stat-sub { color: var(--la-fg-muted); font-size: 11px; margin-top: 4px;
            white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 220px; }

.block { background: var(--la-bg-elevated); border: 1px solid var(--la-border); }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.muted { color: var(--la-fg-muted); font-size: 12px; margin-top: 4px; }
.muted-inline { color: var(--la-fg-muted); font-size: 12px; margin-left: 12px; }
.form-actions { display: flex; align-items: center; margin-left: 120px; }
:deep(.el-card__header) { color: var(--la-fg); border-bottom: 1px solid var(--la-border); }
</style>
