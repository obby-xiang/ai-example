/**
 * `isAiConversationPath` 与后端 `TenantIdentityFilter` 的边界口径对齐。
 *
 * <p>后端排除面只有两类：`health` / `tools` / `tenants` 的**精确路径**，以及
 * `providers` / `models` / `settings` 的**整棵子树**。其余 `/api/ai` 根路径及其下的入口（含
 * `startup-resume` 的读取与扫描、`tools/...` 子路径）都在身份边界内。
 */
import { describe, expect, it } from 'vitest'
import { isAiConversationPath } from '@/utils/ai-identity'

describe('isAiConversationPath 身份边界', () => {
  it('startup-resume 读取与扫描属于身份域', () => {
    expect(isAiConversationPath('/ai/startup-resume')).toBe(true)
    expect(isAiConversationPath('/api/ai/startup-resume')).toBe(true)
    expect(isAiConversationPath('/ai/startup-resume/scan')).toBe(true)
    expect(isAiConversationPath('/api/ai/startup-resume/scan')).toBe(true)
    expect(isAiConversationPath('/api/ai/startup-resume?full=1')).toBe(true)
  })

  it('裸 ai 根路径与后端一致属于身份域', () => {
    expect(isAiConversationPath('/ai')).toBe(true)
    expect(isAiConversationPath('/api/ai')).toBe(true)
    expect(isAiConversationPath('/api/ai/')).toBe(true)
    expect(isAiConversationPath('/api/ai?probe=1')).toBe(true)
    // 防放宽过头：非 ai 前缀与已排除入口不受影响。
    expect(isAiConversationPath('/api/aix')).toBe(false)
    expect(isAiConversationPath('/api/ai/tools')).toBe(false)
  })

  it('tools 仅精确路径排除，子路径仍属于身份域', () => {
    expect(isAiConversationPath('/ai/tools')).toBe(false)
    expect(isAiConversationPath('/api/ai/tools')).toBe(false)
    expect(isAiConversationPath('/api/ai/tools/')).toBe(false)
    expect(isAiConversationPath('/api/ai/tools?scope=all')).toBe(false)
    expect(isAiConversationPath('/ai/tools/echo')).toBe(true)
    expect(isAiConversationPath('/api/ai/tools/echo')).toBe(true)
    expect(isAiConversationPath('/api/ai/tools/echo?verbose=1')).toBe(true)
  })

  it('health 与 tenants 仅精确路径排除，子路径仍属于身份域', () => {
    expect(isAiConversationPath('/ai/health')).toBe(false)
    expect(isAiConversationPath('/ai/tenants')).toBe(false)
    expect(isAiConversationPath('/ai/health/probe')).toBe(true)
    expect(isAiConversationPath('/api/ai/tenants/7/users')).toBe(true)
  })

  it('providers models settings 整棵子树排除', () => {
    expect(isAiConversationPath('/ai/providers')).toBe(false)
    expect(isAiConversationPath('/ai/models/gpt')).toBe(false)
    expect(isAiConversationPath('/api/ai/settings/ai')).toBe(false)
    expect(isAiConversationPath('/api/ai/providerz')).toBe(true)
  })
})
