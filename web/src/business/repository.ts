import { SKILL_LIMIT_BYTES, type SKILLS } from '@/constants/workbench'

export type SkillSlot = (typeof SKILLS)[number]['slot']
export interface SkillState {
  slot: SkillSlot
  custom: boolean
  enabled: boolean
}
export interface RepositoryInput {
  url: string
  standardPaths: string[]
  specPaths: string[]
}
export interface Repository extends RepositoryInput {
  id: number
  platform: 'github' | 'gitlab'
  remoteId: string | number
  fullName: string
  skills: SkillState[]
}
export function splitPaths(text: string) {
  return [
    ...new Set(
      text
        .split(/\r?\n/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ]
}
export async function readSkill(file: File) {
  if (file.name.toLowerCase() !== 'skill.md') throw new Error('请选择名为 SKILL.md 的文件。')
  if (!file.size || file.size > SKILL_LIMIT_BYTES)
    throw new Error('SKILL.md 不能为空，且不能超过 64 KiB。')
  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
  } catch {
    throw new Error('无法读取文件，请确认 SKILL.md 使用 UTF-8 编码。')
  }
  if (!text.trim() || text.includes('\u0000')) throw new Error('SKILL.md 必须包含有效文本。')
  return text
}
