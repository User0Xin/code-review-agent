<script setup lang="ts">
import { useRepositories } from '@/stores/repositories'
const repositories = useRepositories()
defineProps<{ disabled?: boolean }>()
function change(event: Event) {
  repositories.select(Number((event.target as HTMLSelectElement).value))
}
</script>
<template>
  <div v-if="repositories.items.length" class="repo-select">
    <label for="current-repository">当前仓库</label>
    <select
      id="current-repository"
      data-testid="repo-select"
      :disabled="disabled"
      :value="repositories.selectedId"
      @change="change"
    >
      <option v-for="repo in repositories.items" :key="repo.id" :value="repo.id">
        {{ repo.fullName }}
      </option>
    </select>
  </div>
</template>
