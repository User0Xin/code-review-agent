<script setup lang="ts">
import BlankState from '@/components/BlankState.vue'
import RepoSelect from '@/components/RepoSelect.vue'
import { useRepositories } from '@/stores/repositories'
import RepositoryJobs from './components/RepositoryJobs.vue'
const repositories = useRepositories()
</script>
<template>
  <div class="page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">REVIEW ACTIVITY</p>
        <h1>评审任务</h1>
        <p>按仓库查看评审进度，或为指定的 PR / MR 发起一次评审。</p>
      </div>
    </header>
    <RepoSelect /><RepositoryJobs
      v-if="repositories.current"
      :key="repositories.current.id"
      :repository="repositories.current"
    />
    <BlankState
      v-else
      title="先添加一个仓库"
      text="保存仓库链接和评审配置后，即可发起评审。"
      icon="repo"
      ><RouterLink class="button empty-action" to="/repositories"
        >前往仓库配置</RouterLink
      ></BlankState
    >
  </div>
</template>
