import type { Repository, RepositoryInput, SkillSlot } from '@/business/repository'
import { request } from '@/utils/request'

export function listRepositories() {
  return request<Repository[]>({ url: '/repositories' })
}
export function saveRepository(input: RepositoryInput, id?: number) {
  return request<Repository>({
    url: id === undefined ? '/repositories' : '/repositories/' + id,
    method: id === undefined ? 'POST' : 'PUT',
    data: input,
  })
}
export function updateSkill(
  id: number,
  slot: SkillSlot,
  data: { content?: string; enabled?: boolean },
) {
  return request<Repository>({
    url: '/repositories/' + id + '/skills/' + slot,
    method: 'PUT',
    data,
  })
}
export function restoreSkill(id: number, slot: SkillSlot) {
  return request<Repository>({ url: '/repositories/' + id + '/skills/' + slot, method: 'DELETE' })
}
