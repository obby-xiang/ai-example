/**
 * harness 自检（先红后绿的第一条）：
 * 证明「自己起的三件套」真的在跑，且端口使用纪律成立（绝不落在常开的 8081 / 5202）。
 */
import { expect, test } from '@playwright/test'

const API = process.env.E2E_API_BASE ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '18330'}`
const STUB = `http://127.0.0.1:${process.env.E2E_STUB_PORT ?? '18399'}`
const APP = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT ?? '18401'}`
const MODE = process.env.E2E_MODE === 'real' ? 'real' : 'stub'

test.describe('@stub @harness 基建自检', () => {
  test('E2E-HARNESS-01 后端健康 + 前端可达 + 端口纪律', async ({ request }) => {
    expect(APP).not.toContain(':8081')
    expect(APP).not.toContain(':5202')
    expect(API).not.toContain(':8081')
    expect(API).not.toContain(':5202')

    const app = await request.get(`${APP}/`)
    expect(app.status()).toBe(200)

    const health = await request.get(`${API}/api/ai/health`)
    expect(health.status()).toBe(200)
    const body = await health.json()
    expect(body.available).toBe(true)
    expect(body.redis?.available).toBe(true)
  })

  test('E2E-HARNESS-02 桩上游在线（仅桩模式）', async ({ request }) => {
    test.skip(MODE === 'real', '真模型模式不启桩上游')
    const probe = await request.get(`${STUB}/probe`)
    expect(probe.status()).toBe(200)
  })
})
