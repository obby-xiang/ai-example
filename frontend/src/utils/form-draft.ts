/**
 * 生成式表单的**浏览器草稿镜像**（GF 优化批 G-B）。
 *
 * <h2>口径（用户逐条裁定，不得偏离）</h2>
 * <ul>
 * <li>只存 <b>sessionStorage</b>：按 {@code toolCallId} 一键一份，刷新后能在同一页签恢复；</li>
 * <li><b>永不进模型上下文</b>：草稿只在本地读写，回灌（{@code POST /api/ai/frontend-tool-result}）
 * 只发生在用户点"提交"那一次；</li>
 * <li><b>永不写服务器</b>：没有任何上传/持久化接口；</li>
 * <li>提交 / 取消 / 终态即清（{@link clearFormDraft}），不留残留。</li>
 * </ul>
 *
 * <p>为什么不用 localStorage：草稿是"这一次填写"的临时态，跨页签/跨天恢复会带来
 * "打开表单看到上次的脏值"的困惑，也扩大了敏感输入在本机的驻留面。
 */

export const FORM_DRAFT_PREFIX = 'cm.ai.form.draft.'

/** 草稿镜像键（按 toolCallId 隔离；同会话多张表单互不覆盖）。 */
export function formDraftKey(toolCallId: string): string {
  return `${FORM_DRAFT_PREFIX}${toolCallId}`
}

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    // 隐私模式/被禁用：草稿能力静默降级（表单照常可填，只是刷新不恢复）
    return null
  }
}

/** 读草稿：不存在 / 非法 JSON / 非对象一律 null（不把畸形值塞进受控控件）。 */
export function loadFormDraft(toolCallId: string): Record<string, unknown> | null {
  if (!toolCallId) {
    return null
  }
  const target = storage()
  if (!target) {
    return null
  }
  try {
    const raw = target.getItem(formDraftKey(toolCallId))
    if (!raw) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return null
    }
    return { ...(parsed as Record<string, unknown>) }
  } catch {
    return null
  }
}

/** 写草稿（每次字段改动调用一次；容量/序列化异常静默吞掉，不影响填写）。 */
export function saveFormDraft(toolCallId: string, values: Record<string, unknown>): void {
  if (!toolCallId) {
    return
  }
  const target = storage()
  if (!target) {
    return
  }
  try {
    target.setItem(formDraftKey(toolCallId), JSON.stringify(values))
  } catch {
    // QuotaExceeded 等：草稿只是体验增强，绝不因此打断填写
  }
}

/** 清草稿（提交成功 / 取消 / 终态；幂等）。 */
export function clearFormDraft(toolCallId: string): void {
  if (!toolCallId) {
    return
  }
  const target = storage()
  if (!target) {
    return
  }
  try {
    target.removeItem(formDraftKey(toolCallId))
  } catch {
    // 同上：清理失败不影响主流程
  }
}
