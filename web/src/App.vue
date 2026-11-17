<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import WorkIcon from '@/components/WorkIcon.vue'
import WorkbenchAccess from '@/components/WorkbenchAccess.vue'
import { setToken, workbenchToken, unlockHint } from '@/utils/session'

const route = useRoute()
const { t } = useI18n()
const drawer = ref<HTMLDialogElement>()
const menuButton = ref<HTMLButtonElement>()
const links = [
  { to: '/repositories', label: 'shell.repository', icon: 'repo' },
  { to: '/reviews', label: 'shell.jobs', icon: 'review' },
] as const
function closeMenu() {
  if (drawer.value?.open) drawer.value.close()
}
watch(() => route.fullPath, closeMenu)
function restoreFocus() {
  nextTick(() => menuButton.value?.focus())
}
function lock() {
  unlockHint.value = ''
  setToken('')
}
</script>
<template>
  <a class="skip-link" href="#main">{{ t('shell.skip') }}</a>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <img src="/review-mark.svg" alt="" />
        <div>{{ t('shell.brand') }}<small>REVIEW DESK</small></div>
      </div>
      <span class="nav-label">{{ t('shell.workbench') }}</span>
      <nav :aria-label="t('shell.navigation')">
        <RouterLink v-for="link in links" :key="link.to" :to="link.to"
          ><WorkIcon :name="link.icon" />{{ t(link.label) }}</RouterLink
        >
      </nav>
    </aside>
    <div class="surface">
      <header class="topbar">
        <button
          ref="menuButton"
          class="icon-button menu-button"
          :aria-label="t('shell.open')"
          @click="drawer?.showModal()"
        >
          <WorkIcon name="menu" />
        </button>
        <span>{{ t('shell.workbench') }}</span
        ><span class="crumb-separator">/</span
        ><strong>{{ t(route.name === 'reviews' ? 'shell.jobs' : 'shell.repository') }}</strong>
        <button v-if="workbenchToken" class="button subtle lock-button" @click="lock">
          <WorkIcon name="lock" />锁定工作台
        </button>
      </header>
      <main id="main" tabindex="-1">
        <WorkbenchAccess><RouterView /></WorkbenchAccess>
      </main>
    </div>
  </div>
  <dialog ref="drawer" class="nav-dialog" :aria-label="t('shell.navigation')" @close="restoreFocus">
    <div class="drawer-title">
      <strong>{{ t('shell.navigation') }}</strong
      ><button class="icon-button" autofocus :aria-label="t('shell.close')" @click="closeMenu">
        <WorkIcon name="close" />
      </button>
    </div>
    <nav>
      <RouterLink v-for="link in links" :key="link.to" :to="link.to" @click="closeMenu"
        ><WorkIcon :name="link.icon" />{{ t(link.label) }}</RouterLink
      >
    </nav>
  </dialog>
</template>
