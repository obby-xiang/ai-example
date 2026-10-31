/**
 * 11 份 Playwright 配置的 viewport 口径（静态断言，不起浏览器、不读 @playwright/test）。
 *
 * 为什么必须连顺序一起锁：顶层 `use` 与项目级 `use` 是**合并**关系，且 `project.use` 合并在后
 * （同名键由它覆盖）。`devices['Desktop Chrome']` 自带 `viewport: 1280×720`，所以只写顶层
 * `use.viewport` 时实际生效的仍是 1280×720 —— "值对了但被设备预设盖掉"是最容易漏的形态。
 * 故判据 = 每份配置的 `project.use` 在 `...devices[...]` 展开**之后**显式写回 1440×900。
 *
 * 清单口径：`playwright.config.ts` + `playwright.<域>.config.ts` + `e2e/playwright.<域>.config.ts`。
 * 配置面变化（新增/删除域配置）时本断言会红，提醒同改 viewport 口径。
 */
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const FRONTEND = process.cwd()

const CONFIGS = [
  'playwright.config.ts',
  'playwright.hgta.config.ts',
  'playwright.kqc-compact.config.ts',
  'playwright.kqc-special.config.ts',
  'playwright.kqc.config.ts',
  'playwright.mdo.config.ts',
  'playwright.mdxy.config.ts',
  'playwright.unlock-budget.config.ts',
  'playwright.vl.config.ts',
  'e2e/playwright.gf2.config.ts',
  'e2e/playwright.nfezui.config.ts'
] as const

/** `playwright.config.ts` / `playwright.<域>.config.ts`。 */
const CONFIG_FILE = /^playwright(\.(.+))?\.config\.ts$/

/** project.use 内：devices 展开在前、viewport 在后（同一个对象字面量，逗号分隔）。 */
const PROJECT_USE_VIEWPORT =
  /use:\s*\{\s*\.\.\.devices\[[^\]]*\]\s*,\s*viewport:\s*\{\s*width:\s*1440\s*,\s*height:\s*900\s*\}\s*\}/

function discoveredConfigs(): string[] {
  const top = readdirSync(FRONTEND).filter((file) => CONFIG_FILE.test(file))
  const e2e = readdirSync(path.join(FRONTEND, 'e2e'))
    .filter((file) => CONFIG_FILE.test(file))
    .map((file) => `e2e/${file}`)
  return [...top, ...e2e].sort()
}

describe('Playwright 配置：project.use.viewport = 1440×900 且位于 devices 展开之后', () => {
  it('配置清单与目录实况一致（恰好 11 份；增删域配置时同改本清单）', () => {
    expect(discoveredConfigs()).toEqual([...CONFIGS].sort())
    expect(CONFIGS).toHaveLength(11)
  })

  for (const config of CONFIGS) {
    it(`${config}：project.use 在 devices 展开之后写回 1440×900`, () => {
      const source = readFileSync(path.join(FRONTEND, config), 'utf8')
      const projectsAt = source.indexOf('projects:')
      expect(projectsAt, `${config} 里找不到 projects:`).toBeGreaterThan(-1)

      // 只看 projects 之后的部分：顶层 use.viewport 也写成 1440×900，但被设备预设盖掉，
      // 不能拿它当"已生效"的证据。
      const projects = source.slice(projectsAt)
      expect(projects).toMatch(PROJECT_USE_VIEWPORT)

      // 顺序再显式判一次：正则已隐含，但失败时这条给出可直接定位的差值
      expect(projects.indexOf('viewport:')).toBeGreaterThan(projects.indexOf('...devices['))
    })
  }
})
