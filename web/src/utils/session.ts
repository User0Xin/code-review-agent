import { ref } from 'vue'
import { TOKEN_KEY } from '@/constants/workbench'

export const workbenchToken = ref(sessionStorage.getItem(TOKEN_KEY) || '')
export const unlockHint = ref('')
// 同一令牌重新解锁也是新会话，旧 401 不能使其失效。
export let sessionVersion = 0
export function setToken(token: string) {
  sessionVersion++
  workbenchToken.value = token
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}
export function expireSession(token: string, version: number) {
  if (workbenchToken.value !== token || sessionVersion !== version) return
  unlockHint.value = '工作台令牌无效或已过期，请重新输入。'
  setToken('')
}
