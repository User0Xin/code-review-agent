<script setup lang="ts">
import BlankState from '@/components/BlankState.vue'
</script>
<template>
  <div class="page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">REPOSITORIES</p>
        <h1>仓库配置</h1>
        <p>为每个仓库独立管理评审规范、需求文档和 Skill。</p>
      </div>
    </header>
    <BlankState title="仓库工作区" text="仓库的评审配置将在此集中管理。" icon="repo" />
  </div>
</template>
