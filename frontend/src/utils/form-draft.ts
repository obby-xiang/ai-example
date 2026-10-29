/**
 * 生成式表单的**浏览器草稿镜像**（GF 优化批 G-B）。
 *
 * <h2>口径（用户逐条裁定，不得偏离）</h2>
 * <ul>
 * <li>只存 <b>sessionStorage</b>：按 {@code toolCallId} 一键一份，刷新后能在同一页签恢复；</li>
 * <li><b>永不进模型上下文</b>：草稿只在本地读写，回灌（{@code POST /api/ai/frontend-tool-result}）
 * 只发生在用户点"提交"那一次；</li>
 * <li><b>永不写服务器</b>：没有任何上传/持久化接口；</li>
 * <li>提交 / 取消 / 终态即清（{@link clearFormDraft}），不留残留；换会话再按
 * {@link pruneFormDrafts} 的前缀 + 在场白名单清孤儿键（绝不整份 clear）。</li>
 * </ul>
 *
 * <p>为什么不用 localStorage：草稿是"这一次填写"的临时态，跨页签/跨天恢复会带来
 * "打开表单看到上次的脏值"的困惑，也扩大了敏感输入在本机的驻留面。
 */

import { AI_FORM_DRAFT_PREFIX, formDraftPrefix, formDraftStorageKey } from '@/utils/ai-identity'

export const FORM_DRAFT_PREFIX = AI_FORM_DRAFT_PREFIX

/**
 * 草稿镜像键（按 toolCallId 隔离；同会话多张表单互不覆盖）。
 *
 * <p>Task 4：键随**当前身份**加 `tenantId:userId` 指纹（`cm.ai.form.draft.<t>:<u>.<toolCallId>`），
 * 不同身份既看不到也清不掉彼此的草稿；尚无身份时保持旧键形（未接租户目录的部署/单测）。
 */
export function formDraftKey(toolCallId: string): string {
  return formDraftStorageKey(toolCallId)
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

/**
 * 批次6B（N10）：按前缀清**孤儿**草稿 —— 只保留 `present` 里的 `toolCallId`。
 *
 * <p>为什么需要：`clientCleanup.draftToolCallIds` 只枚举服务端还认识的条目，已决卡 / 换会话后
 * 不再被引用的草稿键会长期留在 sessionStorage（无人可见，但不该留）。这里以
 * {@link FORM_DRAFT_PREFIX} **+ 当前身份前缀**为唯一扫描面（不同身份的草稿不互相清理），
 * **绝不** `sessionStorage.clear()` —— 同一页签里还有业务键、镜像键与其它页签状态。
 *
 * @param present 在场白名单（活动表单 / 排队表单 / 仍待动作的工具段引用的 toolCallId）
 * @returns 实际删除的键数（测试与诊断用）
 */
export function pruneFormDrafts(present: Iterable<string>): number {
  const target = storage()
  if (!target) {
    return 0
  }
  const prefix = formDraftPrefix()
  const keep = new Set(present)
  const doomed: string[] = []
  try {
    for (let i = 0; i < target.length; i += 1) {
      const key = target.key(i)
      if (!key || !key.startsWith(prefix)) {
        continue
      }
      const toolCallId = key.slice(prefix.length)
      if (!keep.has(toolCallId)) {
        doomed.push(key)
      }
    }
    doomed.forEach((key) => target.removeItem(key))
  } catch {
    // 隐私模式/配额：清理失败不影响主流程（孤儿键只是卫生问题）
    return 0
  }
  return doomed.length
}
