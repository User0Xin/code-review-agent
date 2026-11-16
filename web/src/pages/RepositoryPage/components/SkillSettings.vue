<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import type { Repository, SkillSlot } from '@/business/repository'
import { readSkill } from '@/business/repository'
import { SKILLS } from '@/constants/workbench'
import { getRepository, restoreSkill, updateSkill } from '@/requests/repositories'
import { messageOf } from '@/utils/request'
const props = defineProps<{ repository: Repository }>()
const emit = defineEmits<{ changed: [Repository] }>()
const busy = ref<SkillSlot>()
const notice = ref('')
const error = ref('')
const confirmSlot = ref<SkillSlot>()
let active = true
onBeforeUnmount(() => {
  active = false
})
function state(slot: SkillSlot) {
  return props.repository.skills.find((item) => item.slot === slot)
}
async function action(slot: SkillSlot, operation: () => Promise<unknown>, success: string) {
  if (busy.value) return
  busy.value = slot
  notice.value = ''
  error.value = ''
  try {
    await operation()
    const fresh = await getRepository(props.repository.id)
    if (active) {
      emit('changed', fresh)
      notice.value = success
    }
  } catch (e) {
    if (active) error.value = messageOf(e)
  } finally {
    if (active) {
      busy.value = undefined
      confirmSlot.value = undefined
    }
  }
}
async function upload(slot: SkillSlot, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file)
    await action(
      slot,
      async () => updateSkill(props.repository.id, slot, { content: await readSkill(file) }),
      'Skill 已导入。',
    )
}
function toggle(slot: SkillSlot) {
  void action(
    slot,
    () => updateSkill(props.repository.id, slot, { enabled: !state(slot)?.enabled }),
    '启用状态已保存。',
  )
}
function restore(slot: SkillSlot) {
  void action(slot, () => restoreSkill(props.repository.id, slot), '已恢复内置 Skill。')
}
</script>
<template>
  <section class="panel">
    <div class="panel-heading">
      <p class="eyebrow">02 / SKILLS</p>
      <h2>仓库 Skill</h2>
      <p class="field-hint">按评审环节启用或导入 UTF-8 编码的 SKILL.md，单个文件不超过 64 KiB。</p>
    </div>
    <div v-for="item in SKILLS" :key="item.slot" class="skill-row">
      <div>
        <div class="row-heading">
          <h3>{{ item.name }}</h3>
          <span class="badge">{{ state(item.slot)?.custom ? '自定义' : '内置' }}</span>
        </div>
        <p class="field-hint">{{ item.description }}</p>
      </div>
      <div class="skill-controls">
        <label class="switch-label"
          ><input
            type="checkbox"
            :checked="state(item.slot)?.enabled"
            :aria-label="'启用' + item.name"
            :data-testid="'skill-toggle-' + item.slot"
            :disabled="!!busy"
            @click.prevent="toggle(item.slot)"
          />启用</label
        >
        <label class="file-action button"
          ><input
            type="file"
            accept=".md,text/markdown"
            :data-testid="'skill-file-' + item.slot"
            :aria-label="'导入' + item.name + ' SKILL.md'"
            :disabled="!!busy"
            @change="upload(item.slot, $event)"
          />导入文件</label
        >
        <button
          v-if="state(item.slot)?.custom"
          class="button subtle"
          :data-testid="'skill-restore-' + item.slot"
          :disabled="!!busy"
          @click="confirmSlot = item.slot"
        >
          恢复内置
        </button>
      </div>
      <div v-if="confirmSlot === item.slot" class="confirm-strip">
        将替换此环节的自定义 Skill。<button
          class="button"
          :disabled="!!busy"
          @click="restore(item.slot)"
        >
          确认恢复</button
        ><button class="button subtle" :disabled="!!busy" @click="confirmSlot = undefined">
          取消
        </button>
      </div>
    </div>
    <p v-if="busy || notice" class="field-hint" role="status">
      {{ busy ? '正在保存 Skill…' : notice }}
    </p>
    <p v-if="error" role="alert" class="error-text">{{ error }}</p>
  </section>
</template>
