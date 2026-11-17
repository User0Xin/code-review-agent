<script setup lang="ts">
import { ref } from 'vue'
import { splitPaths, type Repository } from '@/business/repository'
import { saveRepository } from '@/requests/repositories'
import { messageOf } from '@/utils/request'
const props = defineProps<{ repository?: Repository; canCancel: boolean }>()
const emit = defineEmits<{ saved: [Repository]; cancel: [] }>()
const url = ref(props.repository?.url || '')
const standards = ref(props.repository?.standardPaths.join('\n') || '')
const specs = ref(props.repository?.specPaths.join('\n') || '')
const busy = ref(false)
const error = ref('')
async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    emit(
      'saved',
      await saveRepository(
        {
          url: url.value.trim(),
          standardPaths: splitPaths(standards.value),
          specPaths: splitPaths(specs.value),
        },
        props.repository?.id,
      ),
    )
  } catch (e) {
    error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <form class="panel" @submit.prevent="save">
    <div class="panel-heading">
      <p class="eyebrow">01 / SOURCE</p>
      <h2>{{ repository ? '仓库与文档' : '添加仓库' }}</h2>
    </div>
    <fieldset class="fields" :disabled="busy">
      <div class="field">
        <label for="repo-url">仓库链接</label
        ><input
          id="repo-url"
          v-model="url"
          data-testid="repo-url"
          type="url"
          placeholder="https://github.com/owner/repository"
          :readonly="!!repository"
          required
        />
        <p class="field-hint">
          {{
            repository
              ? '仓库身份已固定。如需评审其他仓库，请添加新仓库。'
              : '输入 GitHub 或已配置 GitLab 平台的 HTTPS 仓库链接。'
          }}
        </p>
      </div>
      <div class="path-fields">
        <div class="field">
          <label for="repo-standards">Coding Standard 路径</label
          ><textarea
            id="repo-standards"
            v-model="standards"
            data-testid="repo-standards"
            rows="3"
            placeholder="coding-standard.md"
            aria-describedby="path-help"
          />
        </div>
        <div class="field">
          <label for="repo-specs">Spec 路径</label
          ><textarea
            id="repo-specs"
            v-model="specs"
            data-testid="repo-specs"
            rows="3"
            placeholder="docs/spec/"
            aria-describedby="path-help"
          />
        </div>
      </div>
      <p id="path-help" class="field-hint">
        每行填写一个仓库内的相对文件或目录路径；留空使用默认发现规则。
      </p>
    </fieldset>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <div class="actions">
      <button class="button primary" data-testid="repo-save" type="submit" :disabled="busy">
        {{ busy ? '正在保存…' : '保存配置' }}</button
      ><button
        v-if="canCancel"
        class="button subtle"
        type="button"
        :disabled="busy"
        @click="emit('cancel')"
      >
        取消
      </button>
    </div>
  </form>
</template>
