<script setup lang="ts">
import WorkIcon from './WorkIcon.vue'
defineProps<{ title: string; text: string; icon: 'repo' | 'review' | 'lock' }>()
</script>
<template>
  <div class="blank-state">
    <div class="blank-icon"><WorkIcon :name="icon" /></div>
    <h2>{{ title }}</h2>
    <p>{{ text }}</p>
    <slot />
  </div>
</template>
