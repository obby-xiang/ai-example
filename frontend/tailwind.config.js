/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  corePlugins: {
    // 与 Element Plus 并存：关闭 preflight 避免重置组件库样式，仅使用工具类
    preflight: false
  },
  theme: {
    extend: {
      colors: {
        brand: {
          from: '#1f3a5f',
          to: '#2f5d8a'
        }
      }
    }
  },
  plugins: []
}
