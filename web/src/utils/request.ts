import axios, { type AxiosRequestConfig } from 'axios'
import { expireSession, sessionVersion, workbenchToken } from '@/utils/session'

interface Result<T> {
  code: number
  msg?: string | null
  data: T
}
export class RequestError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
  ) {
    super(message)
    this.name = 'RequestError'
  }
}
const client = axios.create({ baseURL: '/api', timeout: 30000 })
export async function request<T>(
  config: AxiosRequestConfig,
  isCurrent: () => boolean = () => true,
): Promise<T> {
  const token = workbenchToken.value
  const version = sessionVersion
  const current = () => isCurrent() && workbenchToken.value === token && sessionVersion === version
  if (!token) throw new Error('请先解锁工作台。')
  try {
    const response = await client.request<Result<T>>({
      ...config,
      headers: { Authorization: 'Bearer ' + token },
    })
    if (!current()) throw new Error('请求上下文已更改。')
    if (response.data?.code === 401) expireSession(token, version)
    if (response.data?.code !== 200)
      throw new RequestError(
        response.data?.msg || '服务返回了无法识别的结果。',
        response.data?.code,
      )
    return response.data.data
  } catch (error) {
    if (!axios.isAxiosError(error)) throw error
    // 鉴权失效也属于响应副作用，必须先判断页面是否仍接收这次请求。
    if (!current()) throw new Error('请求上下文已更改。')
    if (error.response?.status === 401) {
      expireSession(token, version)
      throw new Error('工作台令牌无效或已过期，请重新输入。')
    }
    throw new RequestError(
      error.response?.data?.msg ||
        (error.response
          ? '请求失败（HTTP ' + error.response.status + '）。'
          : '无法连接工作台，请检查服务后重试。'),
      error.response?.status,
    )
  }
}
export function messageOf(reason: unknown) {
  return reason instanceof Error ? reason.message : '操作失败，请重试。'
}
