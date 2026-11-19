import type { ReviewJob, ReviewJobDetail } from '@/business/jobs'
import { request } from '@/utils/request'
export function listJobs(repositoryId: number, isCurrent: () => boolean) {
  return request<ReviewJob[]>({ url: '/repositories/' + repositoryId + '/jobs' }, isCurrent)
}
export function getJob(repositoryId: number, jobId: number, isCurrent: () => boolean) {
  return request<ReviewJobDetail>({ url: `/repositories/${repositoryId}/jobs/${jobId}` }, isCurrent)
}
export function startJob(repositoryId: number, number: number, isCurrent: () => boolean) {
  return request<ReviewJob>(
    {
      url: '/repositories/' + repositoryId + '/jobs',
      method: 'POST',
      data: { number },
    },
    isCurrent,
  )
}
