import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { ReviewJobDetail } from './jobs'
import { getJob } from '@/requests/jobs'
import { messageOf, RequestError } from '@/utils/request'
import { workbenchToken } from '@/utils/session'

// 组件按仓库和任务同时置 key，卸载后连错误和 loading 也不能回写。
export function useReviewJobDetail(repositoryId: number, jobId: number) {
  const detail = ref<ReviewJobDetail>()
  const loading = ref(false)
  const error = ref('')
  let alive = true
  async function refresh() {
    if (!alive || loading.value) return
    const token = workbenchToken.value
    const current = () => alive && workbenchToken.value === token
    loading.value = true
    error.value = ''
    try {
      const result = await getJob(repositoryId, jobId, current)
      if (!current()) return
      if (
        result.job.id !== jobId ||
        result.job.repositoryId !== repositoryId ||
        result.repository.id !== repositoryId
      )
        throw new RequestError('详情与所选任务不一致，请刷新后重试。', 404)
      detail.value = result
    } catch (reason) {
      if (!current()) return
      if (reason instanceof RequestError && reason.status === 404) {
        detail.value = undefined
        error.value = '评审任务不存在或不属于所选仓库。'
      } else error.value = messageOf(reason)
    } finally {
      if (current()) loading.value = false
    }
  }
  onMounted(refresh)
  onBeforeUnmount(() => {
    alive = false
  })
  return { detail, loading, error, refresh }
}
