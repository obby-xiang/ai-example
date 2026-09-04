/**
 * ====== 改造 D1/D2：dataVersion 工作区变更感知 ======
 *
 * 设计（plan 文档 §4 改造 D）：
 *   - 单一事实源是页面；所有状态变更收敛到 store 进行拦截
 *   - 版本号方案（非事件流）：任何变更 dataVersion++，AI 只对比数字，不消费事件序列
 *   - dataVersion 持久化到 localStorage（跨刷新连续递增；快照以服务端为准恢复）
 *
 * 三个拦截点（覆盖全部变更源）：
 *   1. store.$onAction 白名单拦截 mutating actions（Pinia 全局插件）
 *   2. router.afterEach（路由变化=上下文变化，版本号照样递增）
 *   3. SpreadJS 编辑事件（组件内 changedHandler 调 bumpDataVersion）
 *
 * 刷新恢复的数据回填不计入变更：beginRestore()/endRestore() 包裹恢复期操作。
 */
import { ref } from 'vue'

const STORAGE_KEY = 'workspace-data-version'

/** 会修改业务数据的 store action 白名单（只读/load 类不计入） */
const MUTATING_ACTIONS = new Set([
  // task store
  'createTask', 'selectScenario', 'updateSelectedDefs', 'saveStepData',
  'gotoStep', 'updateChanges', 'complete', 'remove',
  // config store
  'batchSave', 'applyOperation'
])

function loadVersion() {
  try {
    const v = Number(localStorage.getItem(STORAGE_KEY))
    return Number.isFinite(v) && v > 0 ? Math.floor(v) : 1
  } catch (e) { return 1 }
}

/** 全局唯一版本号（响应式，组件可直接用于倒计时/徽标等展示） */
export const dataVersionRef = ref(loadVersion())

let restoring = false

/** 递增版本号（恢复期被抑制） */
export function bumpDataVersion() {
  if (restoring) return
  dataVersionRef.value++
  try { localStorage.setItem(STORAGE_KEY, String(dataVersionRef.value)) } catch (e) { /* ignore */ }
}

export function getDataVersion() { return dataVersionRef.value }

/** 刷新后的数据恢复操作不应计入变更（plan D1 细则） */
export function beginRestore() { restoring = true }
export function endRestore() { setTimeout(() => { restoring = false }, 0) }

/**
 * 安装三个拦截点中的前两个（store 白名单 + 路由）。
 * 在 main.js 中 pinia/router 创建后调用一次。
 */
export function installDataVersionCapture(pinia, router) {
  // 拦截点 1：mutating store actions
  pinia.use(({ store }) => {
    store.$onAction(({ name, after }) => {
      if (!MUTATING_ACTIONS.has(name)) return
      // after 回调确保 action 成功完成才计版本（失败的操作不改变状态）
      after(() => bumpDataVersion())
    })
  })
  // 拦截点 2：路由变化（不修改业务数据，但改变 AI 对话的上下文环境）
  router.afterEach(() => bumpDataVersion())
}
