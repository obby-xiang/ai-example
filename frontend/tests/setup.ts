// jsdom 缺 ResizeObserver：组件在 onMounted 成功路径上要用它，缺了会被 catch 吞成"引擎加载失败"
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub

// 每例归零 markdown 的自适应降级开关（`src/utils/markdown.ts#degraded`）：该开关是**模块全局**且
// 置位后粘滞（产品语义 = 本会话不再尝试高亮/mermaid），判据却是**墙钟** 24ms 预算 ——
// 并行 worker / 本机负载抖动会让某个用例的单次渲染越预算，其后十余条 mermaid/代码块用例连带变红
// （2026-10-21 实测：父提交 d8056d5 在同样 CPU 压力下可复现同一批 12 条失败）。
// 放在全局 setup 而非单个 spec：任何渲染正文的用例都不得继承上一条用例的时序判定。
import { beforeEach } from 'vitest'
import { resetDegradation } from '@/utils/markdown'

beforeEach(() => {
  resetDegradation()
})
