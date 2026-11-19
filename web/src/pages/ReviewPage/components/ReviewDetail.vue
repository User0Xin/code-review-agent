<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Repository } from '@/business/repository'
import {
  failureStageLabels,
  formatTime,
  safeReviewLink,
  statusLabels,
  type ReviewJob,
} from '@/business/jobs'
import { useReviewJobDetail } from '@/business/useReviewJobDetail'
import WorkIcon from '@/components/WorkIcon.vue'

const props = defineProps<{
  repository: Repository
  jobId: number
  sending: boolean
  submitError: string
  notice: string
}>()
defineEmits<{ close: []; select: [jobId: number]; retry: [job: ReviewJob] }>()
const { detail, loading, error, refresh } = useReviewJobDetail(props.repository.id, props.jobId)
const title = ref<HTMLHeadingElement>()
defineExpose({ focusTitle: () => title.value?.focus(), refresh })
const changeUrl = computed(() =>
  detail.value
    ? safeReviewLink(props.repository, detail.value.job.number, detail.value.changeUrl)
    : undefined,
)
const findings = computed(() =>
  (detail.value?.result.findings || []).map((finding) => ({
    ...finding,
    url: safeReviewLink(props.repository, detail.value!.job.number, finding.discussionUrl, true),
  })),
)
const availabilityLabels = {
  LEGACY_UNLINKED: '旧任务未保存轮次关联，详细结果和精确数量未知。',
  NOT_STARTED: '任务正在排队，审查结果尚未产生。',
  IN_PROGRESS: '任务正在运行；下面仅展示目前已保存的内容，本轮尚未完成。',
  RECORDED: '以下是本轮已保存的审查结果与实际采用的依据。',
  MISSING: '本任务未保存完整审查结果，实际已发布总数未知。',
}
const publishedSummary = computed(() => {
  if (!detail.value) return ''
  const { job, result } = detail.value
  if (result.publishedCount === null)
    return `已记录 ${result.findings.length} 条，实际已发布总数未知。`
  if (job.status === 'SUCCEEDED' && result.publishedCount === 0)
    return '审查完成，本轮没有新增发现。'
  if (job.status === 'FAILED' && result.publishedCount > 0 && result.outcome !== 'SUCCESS')
    return `部分评论已发布，本轮已发布 ${result.publishedCount} 条发现。`
  return `本轮确认已发布 ${result.publishedCount} 条发现。`
})
const retryable = computed(
  () => detail.value?.job.status === 'FAILED' && detail.value.retry.eligible,
)
const checkRemote = computed(
  () =>
    detail.value?.failure?.interrupted ||
    (detail.value?.job.status === 'FAILED' && detail.value.result.publishedCount === null),
)
const documentGroups = computed(() => [
  { label: '编码规范', paths: detail.value?.result.standardDocumentPaths },
  { label: 'spec', paths: detail.value?.result.specDocumentPaths },
])
</script>
<template>
  <section
    class="panel review-detail"
    data-testid="review-detail"
    :data-job-id="jobId"
    :data-repository-id="repository.id"
    aria-labelledby="detail-title"
    :aria-busy="loading"
    @keydown.esc.stop="$emit('close')"
  >
    <div class="history-heading detail-heading">
      <div>
        <p class="eyebrow">REVIEW DETAILS</p>
        <h2 id="detail-title" ref="title" tabindex="-1" data-testid="review-detail-title">
          任务 #{{ jobId }} · 评审详情
        </h2>
        <p class="field-hint">{{ repository.fullName }}</p>
      </div>
      <div class="detail-actions">
        <button
          type="button"
          class="button"
          data-testid="review-detail-refresh"
          :disabled="loading || sending"
          @click="refresh"
        >
          <WorkIcon name="refresh" />刷新详情
        </button>
        <button
          type="button"
          class="button subtle"
          data-testid="review-detail-close"
          @click="$emit('close')"
        >
          返回列表
        </button>
      </div>
    </div>
    <p v-if="loading" class="field-hint" role="status" data-testid="review-detail-loading">
      {{ detail ? '正在刷新详情…' : '正在读取任务详情…' }}
    </p>
    <p v-if="error" class="notice error" role="alert" data-testid="review-detail-error">
      {{ detail ? '刷新失败，以下保留上次读取的内容。' : '' }}{{ error }}
    </p>
    <p v-if="notice" class="notice" role="status" data-testid="review-retry-notice">
      {{ notice }}
    </p>
    <p v-if="submitError" class="notice error" role="alert" data-testid="review-retry-error">
      {{ submitError }}
    </p>
    <template v-if="detail">
      <div class="row-heading">
        <a v-if="changeUrl" :href="changeUrl" target="_blank" rel="noopener noreferrer">
          {{ repository.platform === 'gitlab' ? 'MR' : 'PR' }} #{{ detail.job.number }} · 打开平台
        </a>
        <span v-else
          >{{ repository.platform === 'gitlab' ? 'MR' : 'PR' }} #{{ detail.job.number }}</span
        >
        <span class="badge status-badge" :data-status="detail.job.status">
          {{ statusLabels[detail.job.status] || detail.job.status }}
        </span>
      </div>
      <dl class="detail-facts">
        <div>
          <dt>触发来源</dt>
          <dd>{{ detail.job.source === 'manual' ? '手动触发' : 'Webhook' }}</dd>
        </div>
        <div>
          <dt>提交时间</dt>
          <dd>
            <time :datetime="detail.job.createdAt">{{ formatTime(detail.job.createdAt) }}</time>
          </dd>
        </div>
        <div>
          <dt>{{ detail.failure?.interrupted ? '恢复标记结束' : '结束时间' }}</dt>
          <dd>
            <time v-if="detail.job.finishedAt" :datetime="detail.job.finishedAt">{{
              formatTime(detail.job.finishedAt)
            }}</time
            ><span v-else>尚未结束</span>
          </dd>
        </div>
      </dl>
      <p v-if="detail.failure?.interrupted" class="field-hint">
        结束时间是服务恢复时的标记时间，并非进程实际中断时刻。
      </p>
      <p v-if="detail.job.message" class="job-message">{{ detail.job.message }}</p>

      <div v-if="detail.failure" class="detail-section">
        <h3>失败说明 · {{ failureStageLabels[detail.failure.stage] || '失败阶段未记录' }}</h3>
        <p class="job-message error-text">{{ detail.failure.reason }}</p>
      </div>
      <div v-if="checkRemote" class="notice remote-notice">
        远端可能已收到部分评论，请先打开 {{ repository.platform === 'gitlab' ? 'MR' : 'PR' }}
        核对已发布评论，再重试。
        <a v-if="changeUrl" :href="changeUrl" target="_blank" rel="noopener noreferrer"
          >核对远端评论</a
        >
      </div>
      <div v-if="retryable" class="detail-section retry-section">
        <h3>重试评审</h3>
        <p class="field-hint">
          重试会读取当前 PR/MR 的最新版本，并在执行时使用当前仓库配置；不会重放此任务的旧 SHA。
          原失败记录会保留。
        </p>
        <p v-if="detail.job.finishedAt" class="field-hint">
          同一版本在任务结束三秒内可能复用原任务，是否新建以服务端返回为准。
        </p>
        <div class="actions">
          <button
            v-if="detail.retry.activeJobId !== null"
            type="button"
            class="button"
            data-testid="review-active-job"
            @click="$emit('select', detail.retry.activeJobId)"
          >
            查看正在处理的任务 #{{ detail.retry.activeJobId }}
          </button>
          <button
            v-else
            type="button"
            class="button primary"
            data-testid="review-retry"
            :disabled="sending || loading"
            @click="$emit('retry', detail.job)"
          >
            <WorkIcon name="review" />{{ sending ? '正在提交…' : '重试评审' }}
          </button>
        </div>
      </div>

      <div class="detail-section">
        <h3>评审版本</h3>
        <dl class="detail-versions">
          <div v-for="(sha, label) in detail.diff" :key="label">
            <dt>{{ label }}</dt>
            <dd>
              <code v-if="sha">{{ sha }}</code
              ><span v-else>未取得</span>
            </dd>
          </div>
        </dl>
      </div>
      <div class="detail-section">
        <h3>本轮结果</h3>
        <p class="field-hint">{{ availabilityLabels[detail.result.availability] }}</p>
        <p class="result-summary" role="status">{{ publishedSummary }}</p>
        <p v-if="detail.result.outcome !== null" class="field-hint">
          已保存的审查结果：{{ detail.result.outcome === 'SUCCESS' ? '成功' : '失败' }}
          <template v-if="detail.result.reviewRecordId !== null">
            · 记录 #{{ detail.result.reviewRecordId }}</template
          >
          <template v-if="detail.result.occurredAt">
            ·
            <time :datetime="detail.result.occurredAt">{{
              formatTime(detail.result.occurredAt)
            }}</time></template
          >
        </p>
      </div>
      <div class="detail-section">
        <h3>本轮审查依据</h3>
        <p class="field-hint">
          {{
            detail.result.specSkipped === null
              ? '是否跳过 spec 未记录。'
              : detail.result.specSkipped
                ? '未选中 spec，本轮跳过 spec 审查。'
                : '已采用 spec。'
          }}
        </p>
        <dl class="detail-documents">
          <div v-for="group in documentGroups" :key="group.label">
            <dt>{{ group.label }}</dt>
            <dd>
              <span v-if="group.paths == null">未记录</span>
              <span v-else-if="!group.paths.length">已确定无{{ group.label }}文档</span>
              <ul v-else>
                <li v-for="path in group.paths" :key="path">
                  <code>{{ path }}</code>
                </li>
              </ul>
            </dd>
          </div>
        </dl>
      </div>
      <div class="detail-section">
        <h3>本轮已发布发现</h3>
        <p v-if="!findings.length" class="field-hint">
          {{
            detail.result.publishedCount === 0
              ? '本轮没有新增发现。'
              : '暂无已保存的发现；不能据此认定没有发布评论。'
          }}
        </p>
        <ol v-else class="finding-list" data-testid="review-findings">
          <li
            v-for="finding in findings"
            :key="finding.id"
            :data-testid="'review-finding-' + finding.id"
          >
            <div class="row-heading">
              <strong>{{ finding.category || '未记录类别' }}</strong
              ><span class="field-hint">发现 #{{ finding.id }}</span>
            </div>
            <p class="finding-location">
              <template v-if="finding.path"
                ><code>{{ finding.path }}</code
                ><span v-if="finding.newLine !== null"> · 新侧 {{ finding.newLine }} 行</span
                ><span v-if="finding.oldLine !== null"> · 旧侧 {{ finding.oldLine }} 行</span
                ><span class="field-hint">（记录位置）</span></template
              >
              <template v-else>概览</template>
            </p>
            <p class="finding-body">{{ finding.commentBody }}</p>
            <blockquote v-if="finding.citation" class="finding-citation">
              {{ finding.citation }}
            </blockquote>
            <a v-if="finding.url" :href="finding.url" target="_blank" rel="noopener noreferrer"
              >查看已发布评论</a
            >
            <a v-else-if="changeUrl" :href="changeUrl" target="_blank" rel="noopener noreferrer"
              >打开合并请求核对评论</a
            >
          </li>
        </ol>
      </div>
    </template>
  </section>
</template>
