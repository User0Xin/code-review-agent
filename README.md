# SparkShield

一个仓库对应一个项目，一个 PR / MR 对应一个长期会话。通过 Web 配置模型、仓库和 coding standard，围绕提交版本持续审查、回复讨论，再把多个 PR 的反馈归纳为可采纳的经验。

本分支是全新 TypeScript 实现。第一版面向本地或可信内网的单用户、单实例工作台，使用 SQLite；模型和代码平台均通过真实 API 接入。

## 启动

需要 Node.js 24 LTS 和 pnpm 11.5.0（本地也在 Node.js 26 验证）。

```sh
pnpm install --frozen-lockfile
cp .env.example .env
pnpm dev
```

打开 <http://127.0.0.1:5173>。前端 Vite 代理 `/api` 到后端，后端默认监听 `127.0.0.1:3100`。根目录 `.env` 由启动脚本加载。首次启动自动建立数据库和本地加密密钥。当前开发阶段直接维护当前表结构；启动时事务执行初始化 SQL，不维护数据库迁移版本，也不会自动修改已有表结构或清空数据。

构建后可以由同一个 Node 进程托管 API 与前端：

```sh
pnpm build
pnpm start
```

此时打开 <http://127.0.0.1:3100>。每个数据库只运行一个服务实例；不要同时启动 dev 与 start 访问同一数据库。

## 第一次使用

1. **模型连接**：选择 API Key 或 Codex / ChatGPT 账号。API Key 方式填写名称、API base URL（例如 `https://your-provider/v1`）、模型标识及 API key，需要支持 OpenAI 兼容 Chat Completions 和 JSON Schema 结构化输出。账号方式点击 **Continue with ChatGPT**，在 OpenAI 页面登录并授权使用套餐，返回后选择该账号可用的模型。两种方式均可执行连接测试，验证结构化输出。
2. **绑定仓库**：选择 GitHub 或 GitLab，填写平台地址、`owner/repo`（GitLab 可嵌套 group）、访问令牌和默认模型。GitHub Enterprise 与自建 GitLab 可填写自己的平台地址。令牌需要读取 PR、diff、代码和评论，以及发布评论的权限。
3. **设置规范**：在项目配置中填写 coding standard、忽略路径、自动审查与自动发布策略。规范变更会增加版本；已有轮次保留原配置快照。默认自动审查开启、自动发布关闭。
4. **导入 PR**：选择平台上的开放请求或按编号导入。进入持久会话，点击开始审查，查看轮次、发现、证据与真实代码差异。
5. **发布与讨论**：点击发布当前轮次，向远端发送发现和概览。会话中的“内部讨论”调用 Agent，不向平台发评论；显式选择外部发送时，按用户原文发到 PR，或回复选定的已发布问题线程。
6. **持续审查**：配置 Webhook 后，新提交触发下一轮，保留同一个会话。旧 SHA 的结果不继续发布。CI 事件仅更新对应 SHA 的最近 CI 状态。
7. **积累经验**：对已关闭或已有外部反馈的 PR 生成总结，得到带来源的经验/规范草稿。采纳经验后进入后续审查上下文；采纳规范建议后追加到项目 coding standard。拒绝的建议不生效。

## Webhook

项目设置会显示接收路径：

```text
POST /api/webhooks/github/<project-id>
POST /api/webhooks/gitlab/<project-id>
```

在项目设置和平台设置中填写相同 secret。GitHub 用原始请求体校验 `X-Hub-Signature-256`；GitLab 校验 `X-Gitlab-Token`。必须保留平台投递 ID 请求头，供重复投递去重。

- GitHub：订阅 Pull requests、Pull request review comments、Issue comments，以及需要的 Check runs / Check suites / Workflow runs / Statuses。
- GitLab：订阅 Merge request、Comments、Pipeline / Job events。
- 平台必须能访问接收地址。公网接入时只公开带签名校验的 `/api/webhooks/*`，或在反向代理上为其余路径配置认证。当前 Web 工作台没有账号登录或多租户权限系统，不应直接把整个服务暴露到公网。
- 本地只做手动导入和手动审查时，无需 Webhook 或公网地址。

## 已实现的工作流约束

- SQLite 事务保证 Webhook 收据与任务一起写入，重复投递不会重复入队。
- 单进程 Worker 串行执行持久任务，最多自动尝试 3 次，指数退避；界面可重试最终失败的任务。重启恢复执行中的任务。
- 每轮冻结 SHA、PR 信息、diff、规范版本、模型配置、经验和旧发现；重试使用同一轮快照。
- 发布前再次确认远端 head 和 PR 状态。远端写入与本地存储之间使用持久发布记录和隐藏 marker 对账，降低网络响应丢失造成的重复评论。
- 模型发现必须有可定位文件、有效新侧 diff 行和可核对的原文证据。不执行仓库代码，不给模型平台发布权限。
- 用户在已关联问题线程回复时，Agent 结合最新代码复核并回复；自己的评论回声不会递归触发回复。
- 经验按项目隔离，只有人工采纳的经验或规范进入下一轮。

## 技术与目录

| 位置                           | 职责                                                               |
| ------------------------------ | ------------------------------------------------------------------ |
| `apps/web`                     | React / Vite 工作台，模型与项目配置、PR 会话、diff、经验、任务状态 |
| `apps/server/src/http`         | 按领域拆分的 NestJS Controller、Webhook 入口、SSE 与错误处理       |
| `apps/server/src/workflow`     | 审查、发布、回复、私聊、总结与事件处理                             |
| `apps/server/src/integrations` | GitHub/GitLab、Webhook 事件归一化、AI SDK 模型适配                 |
| `apps/server/src/storage`      | 关系型 Schema、业务 Repository、初始化、任务队列和加密凭据         |
| `apps/server/sql`              | 一张业务表一个初始化 SQL 文件，包含该表索引和触发器                |
| `packages/contracts`           | 前后端共享的 Zod 校验与 TypeScript 领域契约                        |
| `docs/adr`                     | 架构决策                                                           |
| `GLOSSARY.md`                  | 唯一领域术语表                                                     |

前端统一使用 Dub UI 官方组件与默认主题、Lucide、react-resizable-panels。TanStack Query 管理服务端缓存，Zustand 只管理界面状态，React Hook Form + Zod 管理表单，react-diff-view 展示差异。当前没有拖拽功能，因此未引入 dnd-kit。见 [依赖规范](docs/dependencies.md)、[架构说明](docs/architecture.md) 和 [TypeScript 工程约定](docs/engineering.md)。

## 数据、备份与配置

默认数据目录为根目录 `data/`，被 Git 忽略：

- `review.db` 及 SQLite WAL/SHM 文件：项目、PR、轮次、发现、消息、经验、任务、发布记录与加密凭据。
- `master.key`：首次生成的 32 字节本地密钥，文件权限 `0600`。API key、ChatGPT 授权凭据、仓库 token、Webhook secret 使用 AES-256-GCM 加密。
- 也可通过 `REVIEW_ENCRYPTION_KEY` 提供 32 字节 base64 密钥。已经保存凭据后不能直接替换密钥。

备份最简单的方式是先停止服务，再复制整个数据目录。恢复时必须同时恢复数据库及对应密钥。主密钥与数据库同机保存，目的是避免凭据明文落库，不替代主机访问控制或系统密钥服务。

当前结构定义在 `apps/server/sql/*.sql` 与 `apps/server/src/storage/schema.ts`，修改时同步维护两者。初始化使用 `CREATE ... IF NOT EXISTS`，已有数据库需要按实际结构手动调整；构建和部署必须保留 SQL 目录。

`HOST`、`PORT`、`REVIEW_DATABASE_PATH`、`REVIEW_ENCRYPTION_KEY`、`REVIEW_ALLOWED_ORIGINS` 见 `.env.example`。不要在模型 URL 或仓库 URL 中夹带凭据。模型与仓库凭据只能由后端读取，返回界面的配置仅包含是否已配置的标记。

### Codex / ChatGPT 账号连接

账号连接采用 OpenAI 官方 [Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) 的 OAuth + PKCE 流程，使用获授权账号的套餐完成审查、讨论和总结，无需 API Key。账号必须具备套餐使用权限并明确授权；可用模型由账号接口返回，实际访问以连接测试和推理结果为准。用量与额度可在 [ChatGPT 使用情况](https://chatgpt.com/settings/usage) 管理。

首次授权会启动临时的 `127.0.0.1` 回调端口，因此浏览器必须与后端运行在同一台机器。授权窗口会在完成、取消或过期后关闭监听。当前不提供远程服务器或 Docker 主机的回调转发。账号可以分别保存和重新登录，退出账号会撤销会话并清除本地令牌；模型连接仍保留，需重新登录后使用。删除模型连接不等于退出账号，因为同一账号可供多个模型连接使用。

凭据在现有加密库中保存，访问令牌临近过期时串行续期；账号身份、注册 client ID 与本机 host ID 跨重启保留。已有 API Key 配置继续可用，新增账号与模型关联表通过初始化 SQL 创建，无需更改原模型表。

## 验证

```sh
pnpm check
```

执行 ESLint 类型感知检查、Prettier 格式检查、共享契约/前后端及测试代码类型检查、后端测试、生产构建和直接依赖白名单校验。测试覆盖实际 SQLite 持久化、HTTP API、真实平台协议 fixture、模型结构化输出协议、签名验证、更新提交、并发事件去重、发布恢复、反馈及经验审批。CI 使用同一命令。

测试中注入受控平台与模型响应，不会向真实仓库写评论。使用自己的模型与仓库时仍需进行账户权限、网络和模型兼容性联调。

## 当前边界

- SQLite + 同进程串行 Worker 适合单实例；未实现多租户、分布式队列、组织权限、代码平台 OAuth/GitHub App 安装流程、代码平台令牌轮换或多模型回退。
- 审查输入为 PR 描述、diff 和有界文件上下文，不是全仓库检索或执行测试的沙箱。默认最多读取 100 个变更文件、180,000 字符 diff、120,000 字符上下文；代码不完整会显示部分覆盖，不能将“未发现问题”视为完整审查。纯删除行目前不发布旧侧行内评论。
- 分页默认最多 20 页，每页 100 条；不能完整读取评论时会停止发布，避免错误地判断尚未发过评论。
- CI 展示最近收到的匹配 SHA 事件，不聚合所有检查，也不代替代码审查。
- GitHub 时间线评论没有原生线程；只有能关联到已发布问题的讨论才会自动复核。GitHub 行内评论、GitLab discussion 可原线程回复。
- 发现的解决/撤回状态维护在本地；尚未同步关闭远端 discussion。位置移动后的问题采用保守的新发现策略。
- 跨 PR 总结生成草稿，采纳后用于提示词上下文；不进行模型训练，也不自动修改仓库文件。
