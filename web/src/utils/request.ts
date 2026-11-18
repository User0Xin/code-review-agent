import axios, { type AxiosRequestConfig } from 'axios'
import { expireSession, workbenchToken } from '@/utils/session'

interface Result<T> {
  code: number
  msg?: string | null
  data: T
}
const client = axios.create({ baseURL: '/api', timeout: 30000 })
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const token = workbenchToken.value
  if (!token) throw new Error('请先解锁工作台。')
  try {
    const response = await client.request<Result<T>>({
      ...config,
      headers: { Authorization: 'Bearer ' + token },
    })
    if (workbenchToken.value !== token) throw new Error('会话已更改。')
    if (response.data?.code === 401) expireSession(token)
    if (response.data?.code !== 200)
      throw new Error(response.data?.msg || '服务返回了无法识别的结果。')
    return response.data.data
  } catch (error) {
    if (!axios.isAxiosError(error)) throw error
    if (error.response?.status === 401) {
      expireSession(token)
      throw new Error('工作台令牌无效或已过期，请重新输入。')
    }
    throw new Error(
      error.response?.data?.msg ||
        (error.response
          ? '请求失败（HTTP ' + error.response.status + '）。'
          : '无法连接工作台，请检查服务后重试。'),
    )
  }
}
export function messageOf(reason: unknown) {
  return reason instanceof Error ? reason.message : '操作失败，请重试。'
}
