/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  // 关闭 preflight：Tailwind 的基础样式重置会与 Element Plus / SpreadJS 自带样式冲突
  corePlugins: {
    preflight: false
  },
  theme: {
    extend: {
      colors: {
        workspace: '#f5f7fa',
        aiPanel: '#fafbfc'
      },
      // R-165 S5c：三个 AI 面板动画迁出 ai-chat.css（无内置工具类可组合出等价律动，
      // animate-pulse 会丢掉 scale 段、animate-ping 会丢掉透明度停驻段 ⇒ 走 keyframes/animation）。
      // 值逐字取自原 ai-chat.css 的 aiBlink / aiBreathe / aiFadeOut。
      keyframes: {
        aiBlink: {
          '0%, 68%': { opacity: '1' },
          '69%, 100%': { opacity: '0' }
        },
        aiBreathe: {
          '0%, 100%': { opacity: '0.25', transform: 'scale(0.8)' },
          '50%': { opacity: '1', transform: 'scale(1)' }
        },
        aiFadeOut: {
          to: { opacity: '0' }
        }
      },
      animation: {
        caret: 'aiBlink 1s steps(1) infinite',
        breathe: 'aiBreathe 1.5s ease-in-out infinite',
        'fade-out': 'aiFadeOut 0.3s ease forwards'
      }
    }
  },
  plugins: []
}
