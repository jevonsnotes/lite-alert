<script setup lang="ts">
/**
 * 多维分布桑基图（定时任务调用）。
 *
 * 固定 6 个维度顺序：owner -> 任务名称 -> 任务类型 -> 状态码/连接 -> 断言 -> 结果。
 * - 默认全维度开启；点选关闭任一列时，前端按剩余开启列重算相邻边（零网络请求）。
 * - Top X 任务（5/10/20/50/100）切换会重新请求 /breakdown?limit=X，尾部任务由后端合并为「其他」。
 * - 数据来源 GET /api/scheduler/stats/breakdown，按 calls 权限口径分治。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { SankeyChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useThemeStore } from '@/stores/theme'
import { get } from '@/http'

echarts.use([SankeyChart, TooltipComponent, CanvasRenderer])

type BreakdownRow = {
  owner: string
  taskName: string
  taskType: string
  status: string
  assertion: string
  result: string
  count: number
}
type BreakdownResp = {
  rows: BreakdownRow[]
  taskTotals: { taskName: string; count: number }[]
  taskCount: number
  from?: string
  to?: string
}

type DimKey = 'owner' | 'taskName' | 'taskType' | 'status' | 'assertion' | 'result'

const props = defineProps<{
  from: string
  to: string
}>()

const DIMS: { key: DimKey; label: string }[] = [
  { key: 'owner', label: '用户' },
  { key: 'taskName', label: '任务名称' },
  { key: 'taskType', label: '任务类型' },
  { key: 'status', label: '状态码/连接' },
  { key: 'assertion', label: '断言' },
  { key: 'result', label: '结果' }
]

const TOP_OPTIONS = [5, 10, 20, 50, 100]

const theme = useThemeStore()
const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const data = ref<BreakdownResp | null>(null)
const loading = ref(false)
const enabledDims = ref<Set<DimKey>>(new Set(DIMS.map(d => d.key)))
const topLimit = ref(10)

/** Palette shared with the rest of the dashboard charts. */
const PALETTE = ['#3d7cff', '#10b981', '#ef4444', '#f59e0b', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16']

async function load() {
  loading.value = true
  try {
    data.value = await get<BreakdownResp>('/scheduler/stats/breakdown', {
      params: { from: props.from, to: props.to, limit: topLimit.value }
    })
  } catch {
    data.value = null
  } finally {
    loading.value = false
  }
}

/** Active dimensions in their fixed order, after the user toggled some off. */
const activeDims = computed(() => DIMS.filter(d => enabledDims.value.has(d.key)))

const isEmpty = computed(() => !data.value || data.value.rows.length === 0)

const sankeyOption = computed<echarts.EChartsCoreOption | null>(() => {
  const rows = data.value?.rows ?? []
  if (!rows.length || activeDims.value.length < 2) return null

  const dims = activeDims.value
  const nodeSet = new Map<string, { name: string; depth: number }>()
  const linkMap = new Map<string, number>()

  for (const row of rows) {
    // collect nodes per active dimension
    dims.forEach((d, i) => {
      const val = String(row[d.key] ?? '')
      const key = `${i}::${val}`
      if (!nodeSet.has(key)) nodeSet.set(key, { name: val, depth: i })
    })
    // build links between consecutive active dimensions (groupby sum across rows)
    for (let i = 0; i < dims.length - 1; i++) {
      const src = String(row[dims[i].key] ?? '')
      const dst = String(row[dims[i + 1].key] ?? '')
      const lk = `${i}::${src}>>${i + 1}::${dst}`
      linkMap.set(lk, (linkMap.get(lk) ?? 0) + row.count)
    }
  }

  const nodes = Array.from(nodeSet.values()).map((n, idx) => ({
    name: n.name,
    depth: n.depth,
    itemStyle: { color: PALETTE[n.depth % PALETTE.length] }
  }))
  const links = Array.from(linkMap.entries()).map(([k, v]) => {
    const [srcPart, dstPart] = k.split('>>')
    return { source: srcPart.split('::')[1], target: dstPart.split('::')[1], value: v }
  })

  const fg = theme.mode === 'dark' ? '#cdd6f4' : '#1f2937'

  return {
    backgroundColor: 'transparent',
    textStyle: { color: fg },
    tooltip: { trigger: 'item', formatter: (p: any) => {
      if (p.dataType === 'edge') return `${p.data.source} → ${p.data.target}<br/>调用 ${p.data.value}`
      return p.data.name
    }},
    series: [{
      type: 'sankey',
      data: nodes,
      links,
      orient: 'horizontal',
      left: 16, right: 80, top: 16, bottom: 16,
      nodeWidth: 14,
      nodeGap: 8,
      nodeAlign: 'justify',
      lineStyle: { color: 'gradient', opacity: 0.4, curveness: 0.5 },
      label: { color: fg, fontSize: 11 },
      emphasis: { focus: 'adjacency' }
    }]
  }
})

function render() {
  if (!chartEl.value) return
  // The chart lives inside a lazily-shown tab pane; skip init while the container
  // is hidden (0 width) - the ResizeObserver below renders once it becomes visible.
  if (chartEl.value.clientWidth === 0) return
  if (!chart) chart = echarts.init(chartEl.value, theme.mode === 'dark' ? 'dark' : undefined)
  if (sankeyOption.value) {
    chart.setOption(sankeyOption.value, true)
  } else {
    chart.clear()
  }
  chart.resize()
}

function toggleDim(key: DimKey) {
  // never allow fewer than 2 active dimensions (a sankey needs at least source + target)
  const next = new Set(enabledDims.value)
  if (next.has(key)) {
    if (next.size <= 2) return
    next.delete(key)
  } else {
    next.add(key)
  }
  enabledDims.value = next
  nextTick(render)
}

watch(() => theme.mode, () => {
  chart?.dispose()
  chart = null
  nextTick(render)
})

watch([sankeyOption, data], () => nextTick(render))

watch(() => [props.from, props.to], () => load())
watch(topLimit, () => load())

function onResize() { chart?.resize() }

let ro: ResizeObserver | null = null
function onContainerResize() {
  // container went from hidden(0) -> visible(real width): create the chart now if needed
  if (chart) chart.resize()
  else render()
}

onMounted(async () => {
  await load()
  nextTick(render)
  ro = new ResizeObserver(onContainerResize)
  if (chartEl.value) ro.observe(chartEl.value)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="sankey-wrap">
    <div class="toolbar">
      <div class="dim-group">
        <span class="muted-inline">维度：</span>
        <el-check-tag
          v-for="d in DIMS"
          :key="d.key"
          :checked="enabledDims.has(d.key)"
          @change="toggleDim(d.key)"
        >{{ d.label }}</el-check-tag>
      </div>
      <div class="top-group">
        <span class="muted-inline">显示前</span>
        <el-select v-model="topLimit" size="small" style="width: 96px">
          <el-option v-for="n in TOP_OPTIONS" :key="n" :label="`${n} 个任务`" :value="n" />
        </el-select>
        <span v-if="data" class="muted-inline">共 {{ data.taskCount }} 个任务</span>
      </div>
    </div>

    <div v-loading="loading" class="chart-area">
      <div v-if="isEmpty && !loading" class="empty">当前时间区间内无调用记录</div>
      <div v-show="!isEmpty" ref="chartEl" class="chart" />
    </div>
  </div>
</template>

<style scoped>
.sankey-wrap { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.dim-group { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.top-group { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.muted-inline { color: var(--la-fg-muted); font-size: 12px; }
.chart-area { position: relative; }
.chart { width: 100%; height: 460px; }
.empty { color: var(--la-fg-muted); text-align: center; padding: 64px 0; font-size: 13px; }
</style>
