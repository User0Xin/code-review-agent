export type ReviewStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNCERTAIN'
export interface ReviewJob {
  id: number
  repositoryId: number
  number: number
  headSha: string
  status: ReviewStatus
  source: 'manual' | 'webhook'
  message: string | null
  createdAt: string
  finishedAt: string | null
}
export const statusLabels: Record<ReviewStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  UNCERTAIN: '结果待确认',
}
export function isActive(status: ReviewStatus) {
  return status === 'QUEUED' || status === 'RUNNING'
}
export function parseNumber(raw: string) {
  if (!/^\d+$/.test(raw)) return undefined
  const value = Number(raw)
  return Number.isSafeInteger(value) && value > 0 && value <= 2147483647 ? value : undefined
}
const clock = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})
export function formatTime(value: string) {
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? clock.format(timestamp) : value
}
