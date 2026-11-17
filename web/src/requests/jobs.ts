import type { ReviewJob } from '@/business/jobs'
import { request } from '@/utils/request'
export function listJobs(repositoryId: number) {
  return request<ReviewJob[]>({ url: '/repositories/' + repositoryId + '/jobs' })
}
export function startJob(repositoryId: number, number: number) {
  return request<ReviewJob>({
    url: '/repositories/' + repositoryId + '/jobs',
    method: 'POST',
    data: { number },
  })
}
