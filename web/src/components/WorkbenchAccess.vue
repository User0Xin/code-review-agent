<script setup lang="ts">
import { ref, watch } from 'vue'
import WorkIcon from './WorkIcon.vue'
import { useRepositories } from '@/stores/repositories'
import { workbenchToken, unlockHint, setToken } from '@/utils/session'
const repositories = useRepositories()
const input = ref('')
watch(
  workbenchToken,
  (value) => {
    repositories.reset()
    if (value) void repositories.load()
  },
  { immediate: true, flush: 'sync' },
)
function unlock() {
  const token = input.value.trim()
  if (!token) return
  input.value = ''
  unlockHint.value = ''
  setToken(token)
}
</script>
<template>
  <div v-if="!workbenchToken" class="page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">WORKSPACE ACCESS</p>
        <h1>解锁工作台</h1>
        <p>输入管理员提供的工作台令牌以访问仓库和评审任务。</p>
      </div>
    </header>
    <form class="access-form panel" @submit.prevent="unlock">
      <label for="access-token">工作台令牌</label>
      <input
        id="access-token"
        v-model="input"
        data-testid="workbench-token"
        type="password"
        autocomplete="off"
        required
      />
      <p class="field-hint">令牌仅保存在当前浏览器标签页。</p>
      <p v-if="unlockHint" role="alert" class="error-text">{{ unlockHint }}</p>
      <button class="button primary" type="submit" data-testid="workbench-unlock">
        <WorkIcon name="lock" />解锁工作台
      </button>
    </form>
  </div>
  <div v-else-if="repositories.loading || !repositories.loaded" class="page">
    <div v-if="repositories.error" role="alert" class="notice error">
      <p>{{ repositories.error }}</p>
      <button class="button" @click="repositories.load">重新加载</button>
    </div>
    <p v-else role="status" class="field-hint">正在连接工作台…</p>
  </div>
  <slot v-else />
</template>
