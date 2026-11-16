<script setup lang="ts">
import BlankState from '@/components/BlankState.vue'
</script>
<template>
  <div class="page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">REVIEW ACTIVITY</p>
        <h1>评审任务</h1>
        <p>按仓库查看提交的评审及其执行状态。</p>
      </div>
    </header>
    <BlankState title="任务工作区" text="仓库的评审任务将在此显示。" icon="review" />
  </div>
</template>
