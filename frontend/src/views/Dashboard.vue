<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { get } from '@/http'
import SchedulerSankeyChart from '@/components/SchedulerSankeyChart.vue'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

type Namespace = { id: string; name: string }
type Topic = { id: string; name: string; status: string; namespaceId: string; namespaceName?: string }
type ApiKey = { id: string; name: string; prefix: string; status: string }

type Dimension = 'OVERALL' | 'TOPIC' | 'APIKEY'
type ChartMode = 'TREND' | 'RANKING'
type Stats = {
  labels: string[]
  accepted: number[]
  sent: number[]
  failed: number[]
  from?: string
  to?: string
  dimension?: Dimension
  topicId?: string
  apiKeyId?: string
}
type Settings = {
  dashboardDefaultTrend: { value: number; unit: 'DAYS' | 'MONTHS' | 'YEARS' }
}

const auth = useAuthStore()
const theme = useThemeStore()

const namespaces = ref<Namespace[]>([])
const topics = ref<Topic[]>([])
const apikeys = ref<ApiKey[]>([])

const overallChartEl = ref<HTMLDivElement | null>(null)
const topicChartEl = ref<HTMLDivElement | null>(null)
const apiKeyChartEl = ref<HTMLDivElement | null>(null)
let overallChart: echarts.ECharts | null = null
let topicChart: echarts.ECharts | null = null
let apiKeyChart: echarts.ECharts | null = null

const overallStats = ref<Stats | null>(null)
const topicStats = ref<Stats | null>(null)
const apiKeyStats = ref<Stats | null>(null)
const topicRanking = ref<Stats | null>(null)
const apiKeyRanking = ref<Stats | null>(null)

const schedulerStats = ref<{ total: number; success: number; fail: number; successRate: number; trend: { bucket: string; success: boolean; count: number }[] } | null>(null)
const schedulerChartEl = ref<HTMLDivElement | null>(null)
let schedulerChart: echarts.ECharts | null = null

const canViewStats = computed(() => auth.hasPermission('STATS_VIEW'))
const canViewScheduler = computed(() => auth.hasPermission('SCHEDULER_CALL_VIEW') || auth.hasPermission('SCHEDULER_TASK_VIEW'))

const selectedTopicId = ref('')
const selectedApiKeyId = ref('')
const rankingTopicId = ref('')
const rankingApiKeyId = ref('')
const topicMode = ref<ChartMode>('TREND')
const apiKeyMode = ref<ChartMode>('TREND')

const activeTab = ref<'topic' | 'scheduler'>('topic')

// --- global time range (absolute from/to, shared by both tabs) ---

const QUICK_RANGES = [
  { label: '近1天', days: 1 },
  { label: '近1周', days: 7 },
  { label: '近1月', days: 30 },
  { label: '近1年', days: 365 }
]

function toDateStr(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function rangeFromDays(days: number): [Date, Date] {
  const to = new Date()
  const from = new Date()
  from.setDate(to.getDate() - (days - 1))
  return [from, to]
}

const dateRange = ref<[Date, Date]>(rangeFromDays(14))

const fromDateStr = computed(() => toDateStr(dateRange.value[0]))
const toDateStrVal = computed(() => toDateStr(dateRange.value[1]))
const quickActive = computed(() => {
  const [d] = dateRange.value
  const match = QUICK_RANGES.find(r => {
    const [f] = rangeFromDays(r.days)
    return toDateStr(f) === toDateStr(d)
  })
  return match?.label ?? ''
})

function applyQuick(days: number) {
  dateRange.value = rangeFromDays(days)
}
function onPickerChange(val: [Date, Date] | null) {
  if (val && val.length === 2) dateRange.value = val
}

const topicTitle = computed(() => {
  if (topicMode.value === 'RANKING') {
    const t = topics.value.find(x => x.id === rankingTopicId.value)
    return t ? `Topic 调用：${topicLabel(t)}` : 'Topic 调用 Top 10'
  }
  const t = topics.value.find(x => x.id === selectedTopicId.value)
  return t ? `Topic 趋势：${topicLabel(t)}` : 'Topic 趋势'
})
const apiKeyTitle = computed(() => {
  if (apiKeyMode.value === 'RANKING') {
    const k = apikeys.value.find(x => x.id === rankingApiKeyId.value)
    return k ? `ApiKey 调用：${apiKeyLabel(k)}` : 'ApiKey 调用 Top 10'
  }
  const k = apikeys.value.find(x => x.id === selectedApiKeyId.value)
  return k ? `ApiKey 趋势：${k.name}` : 'ApiKey 趋势'
})
const rangeLabel = computed(() => `${fromDateStr.value} ~ ${toDateStrVal.value}`)

function topicLabel(t: Topic) {
  return `${t.namespaceName ? `${t.namespaceName}/` : ''}${t.name}`
}

function apiKeyLabel(k: ApiKey) {
  return `${k.name} (${k.prefix}••••)`
}

async function loadOverview() {
  const [ns, tp, ak] = await Promise.all([
    get<Namespace[]>('/namespaces'),
    get<Topic[]>('/topics'),
    get<ApiKey[]>('/apikeys')
  ])
  namespaces.value = ns
  topics.value = tp
  apikeys.value = ak
  if (!selectedTopicId.value && tp.length) selectedTopicId.value = tp[0].id
  const activeKey = ak.find(k => k.status === 'ACTIVE') ?? ak[0]
  if (!selectedApiKeyId.value && activeKey) selectedApiKeyId.value = activeKey.id
}

function baseParams(dimension: Dimension) {
  return { from: fromDateStr.value, to: toDateStrVal.value, dimension }
}

function rankingParams(dimension: Dimension) {
  return {
    from: fromDateStr.value,
    to: toDateStrVal.value,
    dimension,
    limit: 10,
    topicId: dimension === 'TOPIC' ? rankingTopicId.value || undefined : undefined,
    apiKeyId: dimension === 'APIKEY' ? rankingApiKeyId.value || undefined : undefined
  }
}

async function loadAllStats() {
  if (!canViewStats.value) return
  const requests: Promise<Stats>[] = [
    get<Stats>('/admin/stats/daily', { params: baseParams('OVERALL') }),
    get<Stats>('/admin/stats/daily', {
      params: { ...baseParams('TOPIC'), topicId: selectedTopicId.value || undefined }
    }),
    get<Stats>('/admin/stats/daily', {
      params: { ...baseParams('APIKEY'), apiKeyId: selectedApiKeyId.value || undefined }
    }),
    get<Stats>('/admin/stats/ranking', { params: rankingParams('TOPIC') }),
    get<Stats>('/admin/stats/ranking', { params: rankingParams('APIKEY') })
  ]
  ;[overallStats.value, topicStats.value, apiKeyStats.value, topicRanking.value, apiKeyRanking.value] = await Promise.all(requests)
  await nextTick()
  renderCharts()
}

async function loadTopicStats() {
  if (!canViewStats.value) return
  if (topicMode.value === 'RANKING') {
    topicRanking.value = await get<Stats>('/admin/stats/ranking', { params: rankingParams('TOPIC') })
  } else {
    topicStats.value = await get<Stats>('/admin/stats/daily', {
      params: { ...baseParams('TOPIC'), topicId: selectedTopicId.value || undefined }
    })
  }
  await nextTick()
  renderChart(topicChartEl.value, topicMode.value === 'RANKING' ? topicRanking.value : topicStats.value, 'topic')
}

async function loadApiKeyStats() {
  if (!canViewStats.value) return
  if (apiKeyMode.value === 'RANKING') {
    apiKeyRanking.value = await get<Stats>('/admin/stats/ranking', { params: rankingParams('APIKEY') })
  } else {
    apiKeyStats.value = await get<Stats>('/admin/stats/daily', {
      params: { ...baseParams('APIKEY'), apiKeyId: selectedApiKeyId.value || undefined }
    })
  }
  await nextTick()
  renderChart(apiKeyChartEl.value, apiKeyMode.value === 'RANKING' ? apiKeyRanking.value : apiKeyStats.value, 'apiKey')
}

async function init() {
  try {
    const s = await get<Settings>('/dashboard/settings')
    const days = spanToDays(s.dashboardDefaultTrend)
    dateRange.value = rangeFromDays(days)
  } catch {
    // settings endpoint requires permission; keep the default 14-day window.
  }
  await Promise.all([
    canViewStats.value ? loadAllStats() : Promise.resolve(),
    canViewScheduler.value ? loadSchedulerStats() : Promise.resolve()
  ])
}

async function loadSchedulerStats() {
  if (!canViewScheduler.value) return
  try {
    schedulerStats.value = await get('/scheduler/stats', {
      params: { from: fromIso(dateRange.value[0]), to: toIso(dateRange.value[1]) }
    })
    await nextTick()
    renderSchedulerChart()
  } catch {
    schedulerStats.value = null
  }
}

function fromIso(d: Date): string {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, 0, 0).toISOString()
}
function toIso(d: Date): string {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 23, 59, 59).toISOString()
}

function spanToDays(s: { value: number; unit: 'DAYS' | 'MONTHS' | 'YEARS' }): number {
  if (s.unit === 'MONTHS') return Math.max(1, s.value) * 30
  if (s.unit === 'YEARS') return Math.max(1, s.value) * 365
  return Math.max(1, s.value)
}

function renderSchedulerChart() {
  if (!schedulerChartEl.value || !schedulerStats.value) return
  if (!schedulerChart) schedulerChart = echarts.init(schedulerChartEl.value, theme.mode === 'dark' ? 'dark' : undefined)
  const buckets = [...new Set(schedulerStats.value.trend.map(r => r.bucket))].sort()
  const successOf = (b: string) => schedulerStats.value!.trend.find(r => r.bucket === b && r.success)?.count ?? 0
  const failOf = (b: string) => schedulerStats.value!.trend.find(r => r.bucket === b && !r.success)?.count ?? 0
  const fg = theme.mode === 'dark' ? '#cdd6f4' : '#1f2937'
  const grid = theme.mode === 'dark' ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'
  schedulerChart.setOption({
    backgroundColor: 'transparent',
    textStyle: { color: fg },
    tooltip: { trigger: 'axis' },
    legend: { textStyle: { color: fg }, top: 0 },
    grid: { left: 40, right: 20, top: 36, bottom: 30 },
    xAxis: { type: 'category', data: buckets, axisLabel: { color: fg, fontSize: 11 }, axisLine: { lineStyle: { color: grid } } },
    yAxis: { type: 'value', axisLabel: { color: fg }, splitLine: { lineStyle: { color: grid } } },
    series: [
      { name: '成功', type: 'line', data: buckets.map(successOf), smooth: true, color: '#10b981' },
      { name: '失败', type: 'line', data: buckets.map(failOf), smooth: true, color: '#ef4444' }
    ]
  }, true)
  schedulerChart.resize()
}

function chartFor(kind: 'overall' | 'topic' | 'apiKey') {
  if (kind === 'overall') return overallChart
  if (kind === 'topic') return topicChart
  return apiKeyChart
}

function setChart(kind: 'overall' | 'topic' | 'apiKey', chart: echarts.ECharts) {
  if (kind === 'overall') overallChart = chart
  else if (kind === 'topic') topicChart = chart
  else apiKeyChart = chart
}

function renderCharts() {
  renderChart(overallChartEl.value, overallStats.value, 'overall')
  renderChart(topicChartEl.value, topicMode.value === 'RANKING' ? topicRanking.value : topicStats.value, 'topic')
  renderChart(apiKeyChartEl.value, apiKeyMode.value === 'RANKING' ? apiKeyRanking.value : apiKeyStats.value, 'apiKey')
}

function renderChart(el: HTMLDivElement | null, stats: Stats | null, kind: 'overall' | 'topic' | 'apiKey') {
  if (!el || !stats) return
  let chart = chartFor(kind)
  if (!chart) {
    chart = echarts.init(el, theme.mode === 'dark' ? 'dark' : undefined)
    setChart(kind, chart)
  }
  const fg = theme.mode === 'dark' ? '#cdd6f4' : '#1f2937'
  const grid = theme.mode === 'dark' ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'
  const ranking = kind === 'topic' && topicMode.value === 'RANKING'
    || kind === 'apiKey' && apiKeyMode.value === 'RANKING'
  const labels = ranking ? [...stats.labels].reverse() : stats.labels
  const accepted = ranking ? [...stats.accepted].reverse() : stats.accepted
  const sent = ranking ? [...stats.sent].reverse() : stats.sent
  const failed = ranking ? [...stats.failed].reverse() : stats.failed
  chart.setOption({
    backgroundColor: 'transparent',
    textStyle: { color: fg },
    tooltip: { trigger: 'axis' },
    legend: { textStyle: { color: fg }, top: 0 },
    grid: ranking
      ? { left: 140, right: 20, top: 36, bottom: 30 }
      : { left: 40, right: 20, top: 36, bottom: 30 },
    xAxis: ranking
      ? {
          type: 'value',
          axisLabel: { color: fg, fontSize: 11 },
          axisLine: { lineStyle: { color: grid } },
          splitLine: { lineStyle: { color: grid } }
        }
      : {
          type: 'category',
          data: labels,
          axisLabel: { color: fg, fontSize: 11 },
          axisLine: { lineStyle: { color: grid } }
        },
    yAxis: ranking
      ? {
          type: 'category',
          data: labels,
          axisLabel: { color: fg, width: 80, overflow: 'truncate' },
          splitLine: { lineStyle: { color: grid } }
        }
      : {
          type: 'value',
          axisLabel: { color: fg },
          splitLine: { lineStyle: { color: grid } }
        },
    series: ranking
      ? [
          { name: '受理', type: 'bar', data: accepted, color: '#3d7cff' },
          { name: '送达', type: 'bar', data: sent, color: '#10b981' },
          { name: '失败', type: 'bar', data: failed, color: '#ef4444' }
        ]
      : [
          { name: '受理', type: 'line', data: accepted, smooth: true, color: '#3d7cff' },
          { name: '送达', type: 'line', data: sent, smooth: true, color: '#10b981' },
          { name: '失败', type: 'line', data: failed, smooth: true, color: '#ef4444' }
        ]
  }, true)
  chart.resize()
}

function disposeCharts() {
  overallChart?.dispose()
  topicChart?.dispose()
  apiKeyChart?.dispose()
  schedulerChart?.dispose()
  overallChart = null
  topicChart = null
  apiKeyChart = null
  schedulerChart = null
}

watch(() => theme.mode, () => {
  disposeCharts()
  renderCharts()
  renderSchedulerChart()
})

// global range change -> reload whichever tab(s) the user can see
watch(dateRange, async () => {
  if (canViewStats.value) await loadAllStats()
  if (canViewScheduler.value) await loadSchedulerStats()
})

// lazy-load a tab's charts when first activated (charts need their DOM mounted)
watch(activeTab, async (tab) => {
  await nextTick()
  if (tab === 'topic') renderCharts()
  if (tab === 'scheduler') renderSchedulerChart()
})

onMounted(async () => {
  await loadOverview()
  await init()
  window.addEventListener('resize', onResize)
})

function onResize() {
  overallChart?.resize()
  topicChart?.resize()
  apiKeyChart?.resize()
  schedulerChart?.resize()
}
</script>

<template>
  <div>
    <el-card class="block range-bar">
      <div class="range-row">
        <div class="quick-group">
          <el-button
            v-for="r in QUICK_RANGES"
            :key="r.days"
            size="small"
            :type="quickActive === r.label ? 'primary' : 'default'"
            @click="applyQuick(r.days)"
          >{{ r.label }}</el-button>
        </div>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          size="small"
          value-format="x"
          :clearable="false"
          @change="onPickerChange"
        />
        <span class="muted-inline"><code class="mono">{{ rangeLabel }}</code></span>
      </div>
    </el-card>

    <el-tabs v-model="activeTab" class="dash-tabs">
      <el-tab-pane label="Topic" name="topic">
        <el-row :gutter="16" class="metric-row">
          <el-col :span="6">
            <el-card class="metric"><div class="m-label">命名空间</div><div class="m-value">{{ namespaces.length }}</div></el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="metric"><div class="m-label">Topic</div><div class="m-value">{{ topics.length }}</div></el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="metric"><div class="m-label">已发布 Topic</div><div class="m-value">{{ topics.filter(t => t.status === 'PUBLISHED').length }}</div></el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="metric"><div class="m-label">活跃 ApiKey</div><div class="m-value">{{ apikeys.filter(k => k.status === 'ACTIVE').length }}</div></el-card>
          </el-col>
        </el-row>

        <template v-if="canViewStats">
          <el-card class="block chart-card">
            <template #header>
              <div class="card-head"><span>整体趋势</span></div>
            </template>
            <div ref="overallChartEl" class="chart" />
          </el-card>

          <el-row :gutter="16" class="chart-row">
            <el-col :span="12">
              <el-card class="block chart-card">
                <template #header>
                  <div class="card-head">
                    <span>{{ topicTitle }}</span>
                    <div class="chart-actions">
                      <el-radio-group v-model="topicMode" size="small" @change="loadTopicStats">
                        <el-radio-button :value="'TREND'">趋势</el-radio-button>
                        <el-radio-button :value="'RANKING'">Top10</el-radio-button>
                      </el-radio-group>
                      <el-select v-if="topicMode === 'RANKING'" v-model="rankingTopicId" filterable clearable size="small"
                                 style="width: 220px" placeholder="搜索 Topic" @change="loadTopicStats" @clear="loadTopicStats">
                        <el-option v-for="t in topics" :key="t.id" :label="topicLabel(t)" :value="t.id" />
                      </el-select>
                      <el-select v-else v-model="selectedTopicId" filterable size="small" style="width: 220px" @change="loadTopicStats">
                        <el-option v-for="t in topics" :key="t.id" :label="topicLabel(t)" :value="t.id" />
                      </el-select>
                    </div>
                  </div>
                </template>
                <div ref="topicChartEl" class="chart small-chart" />
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card class="block chart-card">
                <template #header>
                  <div class="card-head">
                    <span>{{ apiKeyTitle }}</span>
                    <div class="chart-actions">
                      <el-radio-group v-model="apiKeyMode" size="small" @change="loadApiKeyStats">
                        <el-radio-button :value="'TREND'">趋势</el-radio-button>
                        <el-radio-button :value="'RANKING'">Top10</el-radio-button>
                      </el-radio-group>
                      <el-select v-if="apiKeyMode === 'RANKING'" v-model="rankingApiKeyId" filterable clearable size="small"
                                 style="width: 220px" placeholder="搜索 ApiKey" @change="loadApiKeyStats" @clear="loadApiKeyStats">
                        <el-option v-for="k in apikeys" :key="k.id" :label="apiKeyLabel(k)" :value="k.id" />
                      </el-select>
                      <el-select v-else v-model="selectedApiKeyId" filterable size="small" style="width: 220px" @change="loadApiKeyStats">
                        <el-option v-for="k in apikeys" :key="k.id" :label="apiKeyLabel(k)" :value="k.id" />
                      </el-select>
                    </div>
                  </div>
                </template>
                <div ref="apiKeyChartEl" class="chart small-chart" />
              </el-card>
            </el-col>
          </el-row>
        </template>

        <el-alert v-else type="info" :closable="false">
          仪表盘趋势图需要统计查看权限。
        </el-alert>
      </el-tab-pane>

      <el-tab-pane label="定时任务" name="scheduler">
        <template v-if="canViewScheduler">
          <el-row :gutter="16" class="metric-row">
            <el-col :span="8">
              <el-card class="metric"><div class="m-label">定时任务调用总数</div><div class="m-value">{{ schedulerStats?.total ?? 0 }}</div></el-card>
            </el-col>
            <el-col :span="8">
              <el-card class="metric"><div class="m-label">成功</div><div class="m-value" style="color:#10b981">{{ schedulerStats?.success ?? 0 }}</div></el-card>
            </el-col>
            <el-col :span="8">
              <el-card class="metric"><div class="m-label">成功率</div><div class="m-value">{{ ((schedulerStats?.successRate ?? 0) * 100).toFixed(1) }}%</div></el-card>
            </el-col>
          </el-row>

          <el-card class="block chart-card">
            <template #header>
              <div class="card-head"><span>定时任务调用趋势</span></div>
            </template>
            <div ref="schedulerChartEl" class="chart" />
          </el-card>

          <el-card class="block chart-card">
            <template #header>
              <div class="card-head"><span>调用多维分布（桑基图）</span></div>
            </template>
            <SchedulerSankeyChart :from="fromIso(dateRange[0])" :to="toIso(dateRange[1])" />
          </el-card>
        </template>
        <el-alert v-else type="info" :closable="false">
          定时任务调用统计需要调用记录查看权限。
        </el-alert>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.range-bar { margin-bottom: 16px; }
.range-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.quick-group { display: flex; gap: 6px; }
.dash-tabs { margin-top: 0; }
.metric-row { margin-bottom: 16px; }
.metric { background: var(--la-bg-elevated); border: 1px solid var(--la-border); }
.m-label { color: var(--la-fg-muted); font-size: 12px; }
.m-value { color: var(--la-accent); font-size: 28px; font-weight: 600; margin-top: 6px; }
.block { background: var(--la-bg-elevated); border: 1px solid var(--la-border); }
.chart-card { margin-bottom: 16px; }
.chart-row { margin-bottom: 16px; }
.chart { width: 100%; height: 320px; }
.small-chart { height: 300px; }
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.chart-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.muted-inline { color: var(--la-fg-muted); font-size: 12px; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
:deep(.el-card__header) { color: var(--la-fg); border-bottom: 1px solid var(--la-border); }
</style>
