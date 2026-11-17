<script setup lang="ts">
import { formatTime, statusLabels, type ReviewJob } from '@/business/jobs'
import BlankState from '@/components/BlankState.vue'
import WorkIcon from '@/components/WorkIcon.vue'
defineProps<{
  jobs: ReviewJob[]
  loaded: boolean
  loading: boolean
  error: string
  platform: 'github' | 'gitlab'
  repositoryUrl: string
}>()
defineEmits<{ refresh: [] }>()
</script>
<template>
  <section class="panel" data-testid="review-jobs" aria-labelledby="history-title">
    <div class="history-heading">
      <div>
        <p class="eyebrow">ACTIVITY LOG</p>
        <h2 id="history-title">评审记录</h2>
      </div>
      <button
        class="button"
        data-testid="review-refresh"
        :disabled="loading"
        @click="$emit('refresh')"
      >
        <WorkIcon name="refresh" />刷新
      </button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="loading && !loaded" role="status" class="field-hint">正在读取任务…</p>
    <ol v-if="jobs.length" class="job-list">
      <li v-for="job in jobs" :key="job.id">
        <div class="job-icon"><WorkIcon name="branch" /></div>
        <div class="job-body">
          <div class="row-heading">
            <h3>
              <a
                :href="
                  repositoryUrl +
                  (platform === 'gitlab' ? '/-/merge_requests/' : '/pull/') +
                  job.number
                "
                target="_blank"
                rel="noopener noreferrer"
                :title="'查看平台上的评审评论'"
                >{{ platform === 'gitlab' ? 'MR' : 'PR' }} #{{ job.number }}</a
              >
            </h3>
            <span class="badge status-badge" :data-status="job.status">{{
              statusLabels[job.status] || job.status
            }}</span>
          </div>
          <div class="job-meta">
            <span>任务 #{{ job.id }}</span
            ><span>{{ job.source === 'manual' ? '手动触发' : 'Webhook' }}</span
            ><code v-if="job.headSha" :title="job.headSha">{{ job.headSha.slice(0, 8) }}</code>
            <span
              >提交
              <time :datetime="job.createdAt" :title="job.createdAt">{{
                formatTime(job.createdAt)
              }}</time></span
            >
            <span v-if="job.finishedAt"
              >结束
              <time :datetime="job.finishedAt" :title="job.finishedAt">{{
                formatTime(job.finishedAt)
              }}</time></span
            >
          </div>
          <p
            v-if="job.message"
            class="job-message"
            :class="{ 'error-text': job.status === 'FAILED' }"
          >
            {{ job.message }}
          </p>
        </div>
      </li>
    </ol>
    <BlankState
      v-else-if="loaded && !error"
      title="还没有评审记录"
      text="手动触发评审或收到仓库事件后，任务会出现在这里。"
      icon="review"
    />
  </section>
</template>
