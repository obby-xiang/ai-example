import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 120000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.response.use(
  (resp) => {
    const data = resp.data
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code === 0) {
        return data
      } else {
        ElMessage.error(data.msg || '请求失败')
        return Promise.reject(new Error(data.msg || 'Error'))
      }
    }
    return data
  },
  (err) => {
    // 改造 F1：用户主动停止(AbortController)触发的取消不弹错误提示，由调用方按「已停止」处理
    if (err.code === 'ERR_CANCELED') return Promise.reject(err)
    const msg = err.response?.data?.msg || err.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default request
