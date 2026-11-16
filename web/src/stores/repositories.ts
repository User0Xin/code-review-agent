import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Repository } from '@/business/repository'
import { REPOSITORY_KEY } from '@/constants/workbench'
import { listRepositories } from '@/requests/repositories'
import { messageOf } from '@/utils/request'
import { workbenchToken } from '@/utils/session'

export const useRepositories = defineStore('repositories', () => {
  const items = ref<Repository[]>([])
  const selectedId = ref<number | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref('')
  const current = computed(() => items.value.find((repo) => repo.id === selectedId.value))
  let version = 0
  function select(id: number | null) {
    selectedId.value = id
    if (id !== null) sessionStorage.setItem(REPOSITORY_KEY, String(id))
    else sessionStorage.removeItem(REPOSITORY_KEY)
  }
  function reset() {
    version++
    items.value = []
    selectedId.value = null
    loading.value = false
    loaded.value = false
    error.value = ''
  }
  function upsert(repo: Repository) {
    const index = items.value.findIndex((item) => item.id === repo.id)
    if (index < 0) items.value.push(repo)
    else items.value[index] = repo
  }
  async function load() {
    const own = ++version
    const token = workbenchToken.value
    loading.value = true
    error.value = ''
    try {
      const rows = await listRepositories()
      if (version !== own || token !== workbenchToken.value) return
      items.value = rows
      const last = Number(sessionStorage.getItem(REPOSITORY_KEY))
      select(rows.find((item) => item.id === last)?.id ?? rows[0]?.id ?? null)
      loaded.value = true
    } catch (e) {
      if (version === own && token === workbenchToken.value) error.value = messageOf(e)
    } finally {
      if (version === own) loading.value = false
    }
  }
  return { items, selectedId, current, loading, loaded, error, select, reset, upsert, load }
})
