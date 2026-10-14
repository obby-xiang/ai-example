<script setup lang="ts">
/**
 * R-163 T5：设置页「AI 反馈」查看界面（闭环四要素 ③）——
 * 时间 / 消息摘要（messageId+sessionId）/ rating（赞/踩）/ reason，仅当前用户（单用户应用全表）。
 * 数据消费方 = 人工复盘，无自动回流；列表只读。
 */
import { onMounted, ref } from 'vue'
import { aiApi, type AiFeedbackEntry } from '@/api/ai'

const items = ref<AiFeedbackEntry[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    items.value = await aiApi.feedbackList()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

function ratingLabel(rating: number): { text: string; cls: string } {
  return rating === 1
    ? { text: '赞', cls: 'text-[#409eff]' }
    : { text: '踩', cls: 'text-[#f56c6c]' }
}

/**
 * R-163 收口修 F2：消息摘要 = 正文快照（excerpt，人工复盘可读）；旧数据/未携时回退 ID 片段。
 */
function summaryOf(item: AiFeedbackEntry): string {
  return item.excerpt && item.excerpt.trim() !== ''
    ? item.excerpt
    : `会话 ${item.sessionId.slice(0, 8)} · 消息 ${item.messageId.slice(0, 8)}`
}
</script>

<template>
  <div class="p-4 max-w-3xl" data-testid="ai-feedback-view">
    <h3 class="text-base font-semibold mb-3">AI 反馈</h3>
    <p class="text-xs text-[#909399] mb-3">赞踩上报记录（最近 100 条，仅当前用户可见），用于人工复盘。</p>
    <div v-if="loading" class="text-sm text-[#909399]">加载中…</div>
    <el-alert v-else-if="error" type="error" :closable="false" :title="`加载失败：${error}`" />
    <el-empty v-else-if="items.length === 0" description="暂无反馈记录" />
    <table v-else class="w-full text-[13px] border-collapse" data-testid="ai-feedback-table">
      <thead>
        <tr class="text-left text-[#909399]">
          <th class="py-1.5 pr-3 font-normal">时间</th>
          <th class="py-1.5 pr-3 font-normal">消息摘要</th>
          <th class="py-1.5 pr-3 font-normal">评价</th>
          <th class="py-1.5 font-normal">原因</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.messageId" class="border-t border-solid border-[#ebeef5]" data-testid="ai-feedback-row">
          <td class="py-1.5 pr-3 text-[#606266] whitespace-nowrap">{{ item.updatedAt?.replace('T', ' ').slice(0, 19) }}</td>
          <td class="py-1.5 pr-3 text-[#606266] max-w-[280px] truncate" :title="summaryOf(item)">{{ summaryOf(item) }}</td>
          <td class="py-1.5 pr-3" :class="ratingLabel(item.rating).cls">{{ ratingLabel(item.rating).text }}</td>
          <td class="py-1.5 text-[#909399]">{{ item.reason ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
