import { ref } from 'vue'
import { TOKEN_KEY } from '@/constants/workbench'

export const workbenchToken = ref(sessionStorage.getItem(TOKEN_KEY) || '')
export const unlockHint = ref('')
export function setToken(token: string) {
  workbenchToken.value = token
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}
export function expireSession(token: string) {
  if (workbenchToken.value !== token) return
  unlockHint.value = '工作台令牌无效或已过期，请重新输入。'
  setToken('')
}
