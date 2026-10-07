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
      }
    }
  },
  plugins: []
}
