<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { Repository } from '@/business/repository'
import { isActive, parseNumber, type ReviewJob } from '@/business/jobs'
import { useReviewJobs } from '@/business/useReviewJobs'
import WorkIcon from '@/components/WorkIcon.vue'
import ReviewHistory from './ReviewHistory.vue'
import ReviewDetail from './ReviewDetail.vue'
const props = defineProps<{ repository: Repository }>()
const {
  rows,
  loading,
  loaded,
  sending,
  error,
  submitError,
  notice,
  refresh,
  submit,
  invalidateSubmission,
} = useReviewJobs(props.repository.id)
const selectedJobId = ref<number>()
const detailView = ref<InstanceType<typeof ReviewDetail>>()
const root = ref<HTMLElement>()
let returnJobId: number | undefined
function focusDetail(jobId: number) {
  void nextTick(() => {
    if (selectedJobId.value === jobId) detailView.value?.focusTitle()
  })
}
function selectJob(jobId: number) {
  if (selectedJobId.value === jobId) {
    if (!sending.value) void detailView.value?.refresh()
  } else {
    invalidateSubmission()
    localError.value = ''
    selectedJobId.value = jobId
  }
  returnJobId = jobId
  focusDetail(jobId)
}
function closeDetail() {
  invalidateSubmission()
  selectedJobId.value = undefined
  void nextTick(() => {
    const button = root.value?.querySelector<HTMLButtonElement>(
      `[data-testid="review-details-${returnJobId}"]`,
    )
    ;(
      button || root.value?.querySelector<HTMLButtonElement>('[data-testid="review-refresh"]')
    )?.focus()
  })
}
async function submitAndOpen(number: number) {
  const result = await submit(number)
  // submit 在切换详情、切仓或锁定后返回 undefined，不能把页面带回旧请求。
  if (!result) return
  if (selectedJobId.value === result.id) void detailView.value?.refresh()
  selectedJobId.value = result.id
  returnJobId = result.id
  focusDetail(result.id)
}
function retry(job: ReviewJob) {
  if (job.id !== selectedJobId.value || job.repositoryId !== props.repository.id) return
  void submitAndOpen(job.number)
}
const numberInput = ref('')
const localError = ref('')
const number = computed(() => parseNumber(numberInput.value.trim()))
const running = computed(() =>
  rows.value.some((item) => item.number === number.value && isActive(item.status)),
)
const targetLabel = props.repository.platform === 'gitlab' ? 'MR 编号' : 'PR 编号'
function trigger() {
  localError.value = ''
  if (number.value === undefined) {
    localError.value = '请输入有效的正整数编号。'
    return
  }
  if (!running.value && !sending.value) void submitAndOpen(number.value)
}
</script>
<template>
  <div ref="root" class="sections">
    <form class="panel" @submit.prevent="trigger">
      <div class="panel-heading">
        <p class="eyebrow">NEW REVIEW</p>
        <h2>手动触发评审</h2>
        <p class="field-hint">
          评审所选仓库的指定
          {{ repository.platform === 'gitlab' ? 'MR' : 'PR' }}，使用该仓库当前的规范和 Skill 配置。
        </p>
      </div>
      <label for="review-number">{{ targetLabel }}</label>
      <div class="trigger-fields">
        <input
          id="review-number"
          v-model="numberInput"
          type="text"
          inputmode="numeric"
          autocomplete="off"
          placeholder="例如 42"
          data-testid="review-number"
          :disabled="sending"
          @input="localError = ''"
        />
        <button
          class="button primary"
          type="submit"
          data-testid="review-trigger"
          :disabled="sending || running"
        >
          <WorkIcon name="review" />{{ sending ? '正在提交…' : '触发评审' }}
        </button>
      </div>
      <p v-if="running" class="field-hint">此编号已有任务正在处理。</p>
      <p v-if="notice && selectedJobId === undefined" class="field-hint" role="status">
        {{ notice }}
      </p>
      <p
        v-if="localError || (submitError && selectedJobId === undefined)"
        role="alert"
        class="error-text"
      >
        {{ localError || submitError }}
      </p>
    </form>
    <ReviewDetail
      v-if="selectedJobId !== undefined"
      :key="repository.id + ':' + selectedJobId"
      ref="detailView"
      :repository="repository"
      :job-id="selectedJobId"
      :sending="sending"
      :submit-error="submitError"
      :notice="notice"
      @close="closeDetail"
      @select="selectJob"
      @retry="retry"
    />
    <ReviewHistory
      :jobs="rows"
      :loading="loading"
      :loaded="loaded"
      :error="error"
      :platform="repository.platform"
      :repository-url="repository.url"
      :selected-job-id="selectedJobId"
      @refresh="refresh"
      @select="selectJob"
    />
  </div>
</template>
