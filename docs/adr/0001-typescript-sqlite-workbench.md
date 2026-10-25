# ADR 0001：TypeScript 单实例审查工作台

状态：已采纳（第一版实现）。

## 背景

产品以仓库和 PR 为中心，要适用于不使用飞书项目或其它任务系统的团队。用户需要 Web 配置模型、仓库与规范，持续审查并从历史反馈学习。后端使用 TypeScript，存储优先轻量 SQLite，前端统一成熟组件。

## 决策

- pnpm workspace：React/Vite 前端、NestJS 后端、共享 Zod/TypeScript 契约。
- SQLite WAL + better-sqlite3 + Drizzle；任务也保存在同一数据库，事务保证事件与任务一致。第一版单进程串行 Worker，不引入 Redis。
- RepositoryPort 隔离 GitHub/GitLab；AgentPort 隔离 AI SDK / OpenAI compatible Chat Completions。模型必须支持 JSON Schema 输出。
- 项目唯一绑定仓库，会话唯一绑定 PR。轮次冻结输入快照，finding 跨轮次维护，publication 单独记录外部副作用。
- Webhook 验证后入队；新提交触发审查，CI 只关联对应 SHA。外部任务系统以后作为可选上下文接入。
- 规范与经验经用户配置/采纳生效。总结是可追溯提示上下文，不是自主训练或自动改写规范。
- UI 使用 Dub UI 官方组件与默认主题（2026-10-23 替换原 shadcn Base UI / Nova 决策），单一功能对应单一依赖。依赖白名单纳入 CI。

## 代价与后续演进条件

SQLite 实现简单但当前 Worker 串行，模型调用会占用队列。需要多实例或高吞吐时再拆分 Worker、数据库和队列；迁移前保留 RepositoryPort、AgentPort 与领域契约。

第一版没有账号体系，默认仅监听回环地址。公网团队部署需要认证与项目权限。当前模型上下文有界；需要整仓检索、依赖调用图或实际执行测试时，增加独立上下文与沙箱能力，不能把部分覆盖当作完整验证。
