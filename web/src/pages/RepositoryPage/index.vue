<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import RepoSelect from '@/components/RepoSelect.vue'
import WorkIcon from '@/components/WorkIcon.vue'
import RepoForm from './components/RepoForm.vue'
import SkillSettings from './components/SkillSettings.vue'
import type { Repository } from '@/business/repository'
import { useRepositories } from '@/stores/repositories'
const repositories = useRepositories()
const adding = ref(false)
const dialog = ref<HTMLDialogElement>()
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
async function startNew() {
  adding.value = true
  notice.value = ''
  await nextTick()
  dialog.value?.showModal()
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
        v-if="repositories.items.length"
        class="button primary"
        data-testid="repo-new"
        @click="startNew"
      >
        <WorkIcon name="plus" />添加仓库
      </button>
    </header>
    <RepoSelect />
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <div class="sections">
      <RepoForm
        v-if="!adding"
        :key="repositories.selectedId ?? 'new'"
        :repository="repositories.current"
        :can-cancel="false"
        @saved="saved"
      />
      <SkillSettings
        v-if="repositories.current"
        :key="repositories.current.id"
        :repository="repositories.current"
        @changed="repositories.upsert"
      />
    </div>
    <dialog
      v-if="adding"
      ref="dialog"
      class="repository-dialog"
      aria-label="添加被审查仓库"
      @cancel="adding = false"
    >
      <RepoForm can-cancel @saved="saved" @cancel="adding = false" />
    </dialog>
  </div>
</template>
