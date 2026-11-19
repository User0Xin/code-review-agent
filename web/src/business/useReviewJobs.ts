import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { isActive, statusLabels, type ReviewJob } from './jobs'
import { listJobs, startJob } from '@/requests/jobs'
import { messageOf } from '@/utils/request'
import { workbenchToken } from '@/utils/session'

// 每个选中的仓库有自己的轮询；切换仓库时旧请求不能改写新视图。
export function useReviewJobs(repositoryId: number) {
  const rows = ref<ReviewJob[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const sending = ref(false)
  const error = ref('')
  const submitError = ref('')
  const notice = ref('')
  const activeCount = computed(() => rows.value.filter((job) => isActive(job.status)).length)
  let alive = true
  let revision = 0
  let submission = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  function schedule() {
    clearTimeout(timer)
    if (alive && document.visibilityState === 'visible')
      timer = setTimeout(refresh, activeCount.value ? 1800 : 9000)
  }
  async function refresh() {
    if (loading.value || !alive) return
    clearTimeout(timer)
    const snapshot = revision
    loading.value = true
    try {
      const result = await listJobs(repositoryId, () => alive && snapshot === revision)
      if (alive && snapshot === revision) {
        rows.value = [...result].sort((a, b) => b.id - a.id)
        loaded.value = true
        error.value = ''
      }
    } catch (e) {
      if (alive && snapshot === revision) error.value = messageOf(e)
    } finally {
      if (alive) {
        loading.value = false
        schedule()
      }
    }
  }
  function invalidateSubmission() {
    submission++
    sending.value = false
    submitError.value = ''
    notice.value = ''
  }
  async function submit(number: number) {
    if (sending.value || !alive) return
    const snapshot = ++submission
    const token = workbenchToken.value
    const current = () => alive && snapshot === submission && workbenchToken.value === token
    sending.value = true
    submitError.value = ''
    notice.value = ''
    try {
      const result = await startJob(repositoryId, number, current)
      if (!current()) return
      if (result.repositoryId !== repositoryId || result.number !== number)
        throw new Error('返回的任务与所选仓库或编号不一致，请刷新列表后重试。')
      revision++
      rows.value = [result, ...rows.value.filter((item) => item.id !== result.id)].sort(
        (a, b) => b.id - a.id,
      )
      loaded.value = true
      // 服务端会复用活动任务和刚结束的任务，返回成功不代表创建了新任务。
      notice.value = `评审任务 #${result.id} ${statusLabels[result.status]}。`
      if (!isActive(result.status)) {
        notice.value += '本次未创建新任务，请在该任务结束三秒后再试。'
      } else {
        notice.value += '已受理或复用已有任务，结果以该任务为准。'
      }
      schedule()
      void refresh()
      return result
    } catch (e) {
      if (current()) submitError.value = messageOf(e)
    } finally {
      if (current()) sending.value = false
    }
  }
  function visibility() {
    if (document.visibilityState === 'visible') void refresh()
    else clearTimeout(timer)
  }
  onMounted(() => {
    void refresh()
    document.addEventListener('visibilitychange', visibility)
  })
  onBeforeUnmount(() => {
    alive = false
    clearTimeout(timer)
    document.removeEventListener('visibilitychange', visibility)
  })
  return {
    rows,
    loading,
    loaded,
    sending,
    error,
    submitError,
    notice,
    activeCount,
    refresh,
    submit,
    invalidateSubmission,
  }
}
