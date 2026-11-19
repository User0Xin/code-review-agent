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
export interface ReviewFinding {
  id: number
  category: '编码规范' | '缺陷' | 'spec' | null
  path: string | null
  newLine: number | null
  oldLine: number | null
  citation: string | null
  commentBody: string
  discussionUrl: string | null
}
export type FailureStage =
  | 'QUEUE'
  | 'DIFF'
  | 'HISTORY'
  | 'STANDARDS'
  | 'SPEC'
  | 'MODEL'
  | 'VERSION_CHECK'
  | 'PUBLISH'
  | 'DISCUSSION_UPDATE'
  | 'EXECUTION'
  | 'INTERRUPTED'
  | 'UNKNOWN'
export interface ReviewJobDetail {
  job: ReviewJob
  repository: { id: number; platform: 'github' | 'gitlab'; fullName: string; url: string }
  changeUrl: string
  diff: { headSha: string | null; baseSha: string | null; startSha: string | null }
  result: {
    availability: 'LEGACY_UNLINKED' | 'NOT_STARTED' | 'IN_PROGRESS' | 'RECORDED' | 'MISSING'
    reviewRecordId: number | null
    outcome: 'SUCCESS' | 'FAILED' | null
    occurredAt: string | null
    publishedCount: number | null
    specSkipped: boolean | null
    specDocumentPaths: string[] | null
    standardDocumentPaths: string[] | null
    findings: ReviewFinding[]
  }
  failure: { stage: FailureStage; reason: string; interrupted: boolean } | null
  retry: { eligible: boolean; activeJobId: number | null }
}
export const failureStageLabels: Record<FailureStage, string> = {
  QUEUE: '任务入队',
  DIFF: '读取变更版本与 diff',
  HISTORY: '读取已有讨论',
  STANDARDS: '选择与读取编码规范',
  SPEC: '选择与读取 spec',
  MODEL: '模型审查',
  VERSION_CHECK: '核对评审版本',
  PUBLISH: '发布或保存发现',
  DISCUSSION_UPDATE: '更新已有讨论',
  EXECUTION: '执行评审',
  INTERRUPTED: '服务重启中断',
  UNKNOWN: '失败阶段未记录',
}

// 平台链接只能落在已登记仓库的同一个合并请求，正文中的链接不参与导航。
export function reviewUrl(
  repository: { platform: 'github' | 'gitlab'; url: string },
  number: number,
) {
  try {
    const base = new URL(repository.url)
    if (
      base.protocol !== 'https:' ||
      base.username ||
      base.password ||
      base.search ||
      base.hash ||
      !Number.isSafeInteger(number) ||
      number <= 0
    )
      return undefined
    base.pathname =
      base.pathname.replace(/\/$/, '') +
      (repository.platform === 'gitlab' ? '/-/merge_requests/' : '/pull/') +
      number
    return base.href
  } catch {
    return undefined
  }
}
export function safeReviewLink(
  repository: { platform: 'github' | 'gitlab'; url: string },
  number: number,
  candidate: string | null,
  discussion = false,
) {
  const expected = reviewUrl(repository, number)
  if (!expected || !candidate) return undefined
  try {
    const link = new URL(candidate)
    const base = new URL(expected)
    const validHash = discussion
      ? (repository.platform === 'gitlab'
          ? /^#note_[1-9]\d*$/
          : /^#(?:issuecomment-|discussion_r)[1-9]\d*$/
        ).test(link.hash)
      : !link.hash
    return link.origin === base.origin &&
      link.pathname === base.pathname &&
      !link.username &&
      !link.password &&
      !link.search &&
      validHash
      ? link.href
      : undefined
  } catch {
    return undefined
  }
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
