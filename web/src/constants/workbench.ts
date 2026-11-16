export const TOKEN_KEY = 'review.workbench.token'
export const REPOSITORY_KEY = 'review.workbench.repository'
export const SKILL_LIMIT_BYTES = 64 * 1024
export const SKILLS = [
  { slot: 'review', name: '代码评审', description: '审查变更并生成问题。' },
  { slot: 'false-positive', name: '误报复核', description: '验证候选问题是否成立。' },
  { slot: 'summary', name: '评审总结', description: '汇总评审结果。' },
] as const
