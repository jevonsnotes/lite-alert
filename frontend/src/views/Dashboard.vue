<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { get } from '@/http'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

type Namespace = { id: string; name: string }
type Topic = { id: string; name: string; status: string; namespaceId: string; namespaceName?: string }
type ApiKey = { id: string; name: string; prefix: string; status: string }

type Unit = 'DAYS' | 'MONTHS' | 'YEARS'
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
  span?: { value: number; unit: Unit }
}
type Settings = {
  dashboardDefaultTrend: { value: number; unit: Unit }
}

const UNITS: { label: string; value: Unit }[] = [
  { label: '天', value: 'DAYS' },
  { label: '月', value: 'MONTHS' },
  { label: '年', value: 'YEARS' }
]

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

const trendValue = ref(14)
const trendUnit = ref<Unit>('DAYS')
const selectedTopicId = ref('')
const selectedApiKeyId = ref('')
const rankingTopicId = ref('')
const rankingApiKeyId = ref('')
const topicMode = ref<ChartMode>('TREND')
const apiKeyMode = ref<ChartMode>('TREND')

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
const canViewStats = computed(() => auth.hasPermission('STATS_VIEW'))
const dateRange = computed(() => {
  const s = overallStats.value ?? topicStats.value ?? apiKeyStats.value
  return s?.from ? `${s.from} ~ ${s.to}` : ''
})

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
  return {
    value: trendValue.value,
    unit: trendUnit.value,
    dimension
  }
}

function rankingParams(dimension: Dimension) {
  return {
    value: trendValue.value,
    unit: trendUnit.value,
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
  if (!canViewStats.value) return
  try {
    const s = await get<Settings>('/dashboard/settings')
    trendValue.value = s.dashboardDefaultTrend.value
    trendUnit.value = s.dashboardDefaultTrend.unit
  } catch {
    // settings endpoint requires permission; users without it keep default trend settings.
  }
  await loadAllStats()
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
  overallChart = null
  topicChart = null
  apiKeyChart = null
}

watch(() => theme.mode, () => {
  disposeCharts()
  renderCharts()
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
}
</script>

<template>
  <div>
    <h2 class="page-h">仪表盘</h2>

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
      <el-card class="block trend-toolbar">
        <div class="trend-picker">
          <span class="muted-inline">趋势窗口：最近</span>
          <el-input-number v-model="trendValue" :min="1" :max="365" size="small"
                           style="width: 90px" @change="loadAllStats" />
          <el-select v-model="trendUnit" size="small" style="width: 80px" @change="loadAllStats">
            <el-option v-for="u in UNITS" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
          <span v-if="dateRange" class="muted-inline">
            <code class="mono">{{ dateRange }}</code>
          </span>
        </div>
      </el-card>

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
  </div>
</template>

<style scoped>
.page-h { color: var(--la-fg); margin-bottom: 16px; }
.metric-row { margin-bottom: 16px; }
.metric { background: var(--la-bg-elevated); border: 1px solid var(--la-border); }
.m-label { color: var(--la-fg-muted); font-size: 12px; }
.m-value { color: var(--la-accent); font-size: 28px; font-weight: 600; margin-top: 6px; }
.block { background: var(--la-bg-elevated); border: 1px solid var(--la-border); }
.trend-toolbar { margin-bottom: 16px; }
.chart-card { margin-bottom: 16px; }
.chart-row { margin-bottom: 16px; }
.chart { width: 100%; height: 320px; }
.small-chart { height: 300px; }
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.chart-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.trend-picker { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.muted-inline { color: var(--la-fg-muted); font-size: 12px; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
:deep(.el-card__header) { color: var(--la-fg); border-bottom: 1px solid var(--la-border); }
</style>
