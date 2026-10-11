// jsdom 缺 ResizeObserver：组件在 onMounted 成功路径上要用它，缺了会被 catch 吞成"引擎加载失败"
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub
