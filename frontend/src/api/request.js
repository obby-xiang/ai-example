import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.response.use(
  res => res,
  err => {
    const msg = err.response?.data?.message || err.message || '请求失败'
    console.error('API Error:', msg, err)
    return Promise.reject(err)
  }
)

export default request
