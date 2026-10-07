<template>
  <div>
    <div class="flex items-center justify-between mb-3">
      <div>
        <h2 class="text-base font-medium m-0">任务中心</h2>
        <p class="text-xs text-gray-500 m-0 mt-1">
          历史任务分页（后端 createdAt DESC, id DESC）+ 一键恢复向导；列表每 4 秒自动刷新。
        </p>
      </div>
      <div class="flex items-center gap-2">
        <el-input v-model="keyword" placeholder="关键词（任务标题）" clearable size="default" class="w-56" />
        <el-select v-model="type" placeholder="类型" clearable class="w-28">
          <el-option label="导出" value="EXPORT" />
          <el-option label="导入" value="IMPORT" />
        </el-select>
        <el-select v-model="status" placeholder="状态" clearable class="w-28">
          <el-option label="进行中" value="ACTIVE" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="已失败" value="FAILED" />
        </el-select>
        <el-button type="primary" @click="apply">查询</el-button>
      </div>
    </div>

    <el-alert
      v-if="taskStore.error"
      type="warning"
      :closable="false"
      show-icon
      :title="`任务列表暂不可用：${taskStore.error}`"
      description="后端不在本棒启动范围内（b/c 棒联调再启）；此处为状态层的真实降级表现，不离线伪造数据。"
      class="mb-3"
    />

    <el-table v-loading="taskStore.loading" :data="taskStore.items" size="default" border>
      <el-table-column label="任务" min-width="200">
        <template #default="{ row }: { row: TaskSummary }">
          <div class="flex items-center gap-2">
            <span class="font-mono text-xs text-gray-500">#{{ row.task.id }}</span>
            <span>{{ row.task.title }}</span>
            <el-tag size="small" :type="row.task.type === 'EXPORT' ? 'primary' : 'success'">
              {{ TASK_TYPE_LABELS[row.task.type] }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="当前步骤" width="120">
        <template #default="{ row }: { row: TaskSummary }">{{ stepLabel(row.task.currentStep) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }: { row: TaskSummary }">
          <el-tag size="small" :type="taskTagType(row.task.status)">{{ TASK_STATUS_LABELS[row.task.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最新作业进度" min-width="180">
        <template #default="{ row }: { row: TaskSummary }">
          <JobProgress
            v-if="row.latestJob"
            :processed="row.latestJob.progress"
            :total="row.latestJob.total"
            :status="row.latestJob.status"
            :error-count="row.latestJob.errorCount"
            :warning-count="row.latestJob.warningCount"
          />
          <span v-else class="text-xs text-gray-400">暂无作业</span>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }: { row: TaskSummary }">{{ formatDateTime(row.task.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }: { row: TaskSummary }">
          <el-button link type="primary" size="small" @click="restore(row)">恢复向导</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="flex justify-end mt-3">
      <el-pagination
        layout="total, sizes, prev, pager, next"
        :total="taskStore.total"
        :current-page="taskStore.page + 1"
        :page-size="taskStore.size"
        :page-sizes="[10, 20, 50]"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 任务中心（本棒：状态层接线 + 最小渲染；详情抽屉/冲突文案面板由 c 棒补）。
 *
 * - 4s 自刷：deepseek `TasksView.vue:154` 形态（tick 前查 loading 防重叠）；
 * - 一键恢复向导：按任务类型跳对应向导页（task store 的 restoreWizard）；
 * - 进度展示为**行级**口径（total 未知显示"处理中"）。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import JobProgress from '@/components/JobProgress.vue'
import { useTaskStore } from '@/stores/task'
import { useWorkspaceStore } from '@/stores/workspace'
import { formatDateTime, stepLabel, TASK_STATUS_LABELS, TASK_TYPE_LABELS } from '@/utils/format'
import type { TaskStatus, TaskSummary } from '@/types/task'

const taskStore = useTaskStore()
const workspace = useWorkspaceStore()
const router = useRouter()

const keyword = ref('')
const type = ref<'EXPORT' | 'IMPORT' | null>(null)
const status = ref<TaskStatus | null>(null)

function apply(): void {
  taskStore.applyFilters({
    keyword: keyword.value,
    type: type.value,
    status: status.value
  })
}

function onPageChange(page: number): void {
  taskStore.setPage(page - 1)
}

function onSizeChange(size: number): void {
  taskStore.setSize(size)
}

function taskTagType(value: TaskStatus): 'success' | 'danger' | 'info' | 'warning' {
  switch (value) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    default:
      return 'warning'
  }
}

async function restore(row: TaskSummary): Promise<void> {
  const target = await taskStore.restoreWizard(row.task.id ?? 0, row.task.type)
  await router.push(target)
}

async function remove(row: TaskSummary): Promise<void> {
  if (row.task.id === undefined) {
    return
  }
  await taskStore.removeTask(row.task.id)
}

onMounted(() => {
  workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null })
  void taskStore.load()
  taskStore.startAutoRefresh()
})

onBeforeUnmount(() => {
  taskStore.stopAutoRefresh()
})
</script>
