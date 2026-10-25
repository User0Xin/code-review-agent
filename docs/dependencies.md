# 前端与依赖约束

优先复用成熟库，组件源码可控，统一设计语言，严格限制重复依赖。**已有依赖能够正确表达组件或行为时，严禁自行重新实现。**

| 能力                      | 唯一实现                                  | 使用范围                                                             |
| ------------------------- | ----------------------------------------- | -------------------------------------------------------------------- |
| UI / 弹窗 / Tabs / Switch | Dub UI (`@dub/ui`)                        | 使用库组件与默认主题；本地仅保留业务 API 和可访问性适配              |
| 图标                      | Lucide                                    | 不混用其它图标体系                                                   |
| 界面颜色                  | Dub 默认主题                              | 官方共享主题 token；页面背景、边框和文字保持中性色                   |
| 分栏布局                  | react-resizable-panels                    | 不手写拖动分隔条                                                     |
| 拖拽                      | dnd-kit（预留）                           | 当前没有需要，不提前安装；引入时更新白名单和决策                     |
| 本地 UI 状态              | Zustand                                   | 选中项目、会话和视图等；不重复保存服务端对象                         |
| 服务端状态                | TanStack Query                            | HTTP、缓存、重新获取；SSE 使查询失效                                 |
| 表单                      | React Hook Form + Zod                     | 前后端共享校验契约，不自写校验框架                                   |
| 代码差异                  | react-diff-view                           | 解析和呈现实际平台 diff                                              |
| 审查文字排版              | react-markdown                            | 标准渲染模型原始 Markdown，保留段落、列表和代码结构，不执行原始 HTML |
| 样式                      | Tailwind CSS + Dub theme + Tailwind forms | 扫描 Dub 包内类名，保留默认组件样式                                  |

`dependency-policy.json` 固定直接依赖白名单，`pnpm check:dependencies` 在 CI 执行。新增依赖前检查已有方案，必要时同步记录选择理由和白名单。`pnpm-lock.yaml` 固定解析结果；CI 使用 frozen lockfile。禁止引入第二套 UI primitive、请求缓存、状态管理、图标或动画库完成相同职责。

模型接口继续使用 AI SDK：`@ai-sdk/openai-compatible` 处理 API Key 的兼容 Chat Completions，`@ai-sdk/openai` 处理 ChatGPT 套餐授权要求的流式 Responses API。现有兼容适配器不支持 Responses，不能直接替换为 OAuth token。OIDC ID token 使用 `jose` 验证 OpenAI JWKS 签名、issuer、audience、有效期与 nonce，避免自行实现 JWT 密码学验证；OAuth HTTP 和本机回调使用 Node 原生能力。

审查总结、问题说明、讨论和跨 PR 总结使用 `react-markdown`，配合官方推荐的 `remark-gfm` 插件解析 GFM 表格、任务列表、删除线等扩展语法，复用同一 Markdown 管线。表格保留语义化行列并在窄屏独立横向滚动；仍不执行原始 HTML，也不加载正文图片。现有 UI 与 diff 依赖没有 Markdown 解析能力。

模型负责输出规范 Markdown，跨 PR 总结按“适用范围、支持证据、建议、局限”四个三级标题分节，每节非空并以空行分隔。后端显式复用 `react-markdown` 已使用的 CommonMark 解析核心 `mdast-util-from-markdown`，在写入草稿前校验整批提案；代码块、引用中的标题不能冒充分节。不合格输出通过现有任务队列重试，最多 3 次，耗尽后显示具体失败原因，不保存不合格或部分草稿。前端仅标准渲染原始 Markdown，保留原段落、列表和代码结构，不自行按句拆段。已有纯文本总结需重新生成后获得分节格式。

官方组件来源和许可证见 `apps/web/src/components/ui/source-provenance.json` 和同目录的 `LICENSE.md`。页面只组合这些组件实现业务行为；业务特有的 API 调用、条件渲染和数据映射由项目代码实现。

Dub 的 Modal 需要 Next router context；直接依赖 `next` 只用于 Vite 中的兼容桥，`@radix-ui/react-dialog` 与 Dub 共用底层上下文，以关联可见标题和描述。通知直接使用 Dub 自带的 Sonner；这些不是第二套 UI 系统。Dub UI 许可证为 AGPL-3.0，enterprise 部分为商业许可，见 `apps/web/THIRD_PARTY_NOTICES.md`。
