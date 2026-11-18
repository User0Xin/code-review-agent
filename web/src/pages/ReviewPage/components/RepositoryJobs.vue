<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Repository } from '@/business/repository'
import { isActive, parseNumber } from '@/business/jobs'
import { useReviewJobs } from '@/business/useReviewJobs'
import WorkIcon from '@/components/WorkIcon.vue'
import ReviewHistory from './ReviewHistory.vue'
const props = defineProps<{ repository: Repository }>()
const { rows, loading, loaded, sending, error, submitError, notice, refresh, submit } =
  useReviewJobs(props.repository.id)
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
  if (!running.value && !sending.value) void submit(number.value)
}
</script>
<template>
  <div class="sections">
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
      <p v-if="notice" class="field-hint" role="status">{{ notice }}</p>
      <p v-if="localError || submitError" role="alert" class="error-text">
        {{ localError || submitError }}
      </p>
    </form>
    <ReviewHistory
      :jobs="rows"
      :loading="loading"
      :loaded="loaded"
      :error="error"
      :platform="repository.platform"
      :repository-url="repository.url"
      @refresh="refresh"
    />
  </div>
</template>
