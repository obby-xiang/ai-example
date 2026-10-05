/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  // 关闭 preflight：避免 Tailwind 的基础样式重置与 Element Plus / SpreadJS 自带样式冲突
  corePlugins: {
    preflight: false
  },
  theme: {
    extend: {}
  },
  plugins: []
}
