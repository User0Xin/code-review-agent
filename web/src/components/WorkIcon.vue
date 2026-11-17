<script setup lang="ts">
const paths = {
  repo: 'M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5Zm0 14a2.5 2.5 0 0 1 2.5-2.5H20M8 7h7',
  review: 'M4 5h16v14H4z M8 9h8 M8 13h5',
  menu: 'M3 6h18 M3 12h18 M3 18h18',
  close: 'm6 6 12 12 M18 6 6 18',
  arrow: 'm9 5 7 7-7 7',
  plus: 'M12 5v14 M5 12h14',
  lock: 'M5 11h14v10H5z M8 11V7a4 4 0 0 1 8 0v4',
  refresh: 'M20 4v5h-5 M19 8a8 8 0 1 0 1 7',
  branch:
    'M6 5v14 M6 16c0-5 10-1 10-9 M4 4a2 2 0 1 0 4 0a2 2 0 1 0-4 0 M4 20a2 2 0 1 0 4 0a2 2 0 1 0-4 0 M14 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0',
}
defineProps<{ name: keyof typeof paths }>()
</script>
<template>
  <svg
    aria-hidden="true"
    class="work-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <path :d="paths[name]" />
  </svg>
</template>
