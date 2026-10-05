<template>
  <div class="ai-panel">
    <div class="ai-header">
      <span class="ai-title">🤖 AI 助手</span>
      <div>
        <el-tag size="small" type="info" class="session-tag">页签会话</el-tag>
        <el-button size="small" text type="danger" @click="onClear" :disabled="!ai.messages.length">清空</el-button>
      </div>
    </div>

    <div class="ai-suggestions" v-if="!ai.messages.length">
      <el-tag v-for="s in suggestions" :key="s" size="small" effect="plain" @click="send(s)">{{ s }}</el-tag>
    </div>

    <div class="ai-messages" ref="msgBox">
      <div v-for="m in ai.messages" :key="m.id">
        <div class="ai-msg user" v-if="m.role === 'user'">
          <div class="ai-bubble">{{ m.content }}</div>
        </div>
        <div v-else class="ai-msg assistant" style="flex-direction: column; align-items: flex-start;">
          <div v-if="m.reasoning" class="ai-reasoning">
            <el-collapse>
              <el-collapse-item :title="'思考过程（' + m.reasoning.length + ' 字）'">
                <div class="reasoning-body">{{ m.reasoning }}</div>
              </el-collapse-item>
            </el-collapse>
          </div>
          <div v-for="(t, ti) in m.tools" :key="ti" class="ai-tool-card">
            <el-collapse>
              <el-collapse-item>
                <template #title>
                  <span class="tool-head">
                    🔧 工具 {{ t.name }}
                    <el-tag v-if="t.status === 'ok'" type="success" size="small">成功</el-tag>
                    <el-tag v-else-if="t.status === 'fail'" type="danger" size="small">失败</el-tag>
                    <el-tag v-else-if="t.status === 'pending'" type="warning" size="small">未执行</el-tag>
                    <el-tag v-else type="info" size="small">执行中</el-tag>
                  </span>
                </template>
                <div class="tool-body">
                  <div><b>参数：</b>{{ JSON.stringify(t.args) }}</div>
                  <div v-if="t.summary"><b>结果：</b>{{ t.summary }}</div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
          <div v-if="m.confirm" class="ai-confirm-card">
            <div>⚠ 请求执行「<b>{{ m.confirm.toolName }}</b>」</div>
            <div class="text-muted">{{ m.confirm.summary }}</div>
            <div style="margin-top: 8px;">
              <el-button size="small" type="primary" @click="onConfirm(true)">确认执行</el-button>
              <el-button size="small" @click="onConfirm(false)">取消</el-button>
            </div>
          </div>
          <div v-if="m.content" class="ai-bubble">{{ m.content }}
            <span v-if="!m.done && ai.streaming" class="cursor">▌</span>
          </div>
          <div v-if="ai.streaming && (!m.content || m.content.length === 0) && m.done === false" class="ai-bubble text-muted">
            思考中…<span class="cursor">▌</span>
          </div>
        </div>
      </div>
    </div>

    <div class="ai-input">
      <el-input v-model="input" type="textarea" :rows="2" resize="none"
                placeholder="描述你的需求，例如：帮我导出所有全局配置"
                @keydown.enter.exact.prevent="send()" />
      <div class="ai-input-btns">
        <el-button v-if="!ai.streaming" type="primary" :disabled="!input.trim()" @click="send()">发送</el-button>
        <el-button v-else type="danger" @click="ai.stop()">停止</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAiStore } from '../stores/ai'

const ai = useAiStore()
const route = useRoute()
const input = ref('')
const msgBox = ref(null)

const suggestions = computed(() => {
  const name = String(route.name)
  if (name === 'export') {
    return ['列出所有配置项', '帮我导出所有全局配置', '导出服务器参数配置中环境为“生产”的数据']
  }
  if (name === 'import') {
    return ['下载所有配置的导入模板', '帮我选择地区计费规则并创建导入批次', '检查我上传的文件并汇总错误']
  }
  if (name === 'defs') {
    return ['查看服务器参数配置的字段定义', '帮我新建一个全局配置定义']
  }
  if (name === 'data') {
    return ['查看服务器参数配置的生效数据', '统计各配置的生效数据行数']
  }
  return ['列出所有配置项']
})

function send(text) {
  const content = (text || input.value).trim()
  if (!content) return
  input.value = ''
  ai.send(content).then(scrollBottom)
}

function onConfirm(approved) {
  ai.confirmTool(approved).then(scrollBottom)
}

async function onClear() {
  await ai.clear()
  ElMessage.success('会话已清空')
}

async function scrollBottom() {
  await nextTick()
  if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
}

watch(() => ai.messages.length, scrollBottom)
</script>

<style scoped>
.ai-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.ai-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}
.ai-title { font-size: 15px; font-weight: 600; color: #303133; }
.session-tag { margin-right: 6px; }
.ai-input-btns { display: flex; flex-direction: column; gap: 4px; }
.cursor { animation: blink 1s infinite; color: #409eff; }
@keyframes blink { 50% { opacity: 0; } }
.reasoning-body { white-space: pre-wrap; font-size: 12px; color: #909399; }
</style>
