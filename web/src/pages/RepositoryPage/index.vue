<script setup lang="ts">
import { ref, watch } from 'vue'
import RepoSelect from '@/components/RepoSelect.vue'
import WorkIcon from '@/components/WorkIcon.vue'
import RepoForm from './components/RepoForm.vue'
import SkillSettings from './components/SkillSettings.vue'
import type { Repository } from '@/business/repository'
import { useRepositories } from '@/stores/repositories'
const repositories = useRepositories()
const adding = ref(false)
const notice = ref('')
watch(
  () => repositories.selectedId,
  () => {
    adding.value = false
    notice.value = ''
  },
  { flush: 'sync' },
)
function saved(repo: Repository) {
  repositories.upsert(repo)
  repositories.select(repo.id)
  adding.value = false
  notice.value = '仓库配置已保存。'
}
function startNew() {
  adding.value = true
  notice.value = ''
}
</script>
<template>
  <div class="page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">REPOSITORIES</p>
        <h1>仓库配置</h1>
        <p>为每个仓库独立管理评审规范、需求文档和 Skill。</p>
      </div>
      <button
        v-if="!adding && repositories.items.length"
        class="button primary"
        data-testid="repo-new"
        @click="startNew"
      >
        <WorkIcon name="plus" />添加仓库
      </button>
    </header>
    <RepoSelect v-if="!adding" />
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <div class="sections">
      <RepoForm
        :key="adding ? 'new' : (repositories.selectedId ?? 'new')"
        :repository="adding ? undefined : repositories.current"
        :can-cancel="adding && !!repositories.items.length"
        @saved="saved"
        @cancel="adding = false"
      />
      <SkillSettings
        v-if="!adding && repositories.current"
        :key="repositories.current.id"
        :repository="repositories.current"
        @changed="repositories.upsert"
      />
    </div>
  </div>
</template>
