# SparkShield Web

React + TypeScript + Vite 的本地工作台，API 使用同源 `/api`；开发服务器 5173 代理到后端 3100。由根目录执行 `pnpm dev`。

## 交互与依赖边界

- Dub UI：`@dub/ui` 提供 Button、Input、Label、Badge、Switch、Modal、TabSelect、Combobox 与 TooltipProvider。`src/components/ui` 只做业务 API 与可访问性适配；默认主题色来自 Dub 官方 `themes.css`，在 `src/dub-theme.css` 中注册为 Tailwind v4 token。所有下拉框统一使用 Combobox 浮层菜单，支持键盘选择、禁用项和可选搜索；textarea 使用 Dub 相同的 Tailwind forms 样式，通知使用 Dub 的 Sonner 依赖。
- Lucide：产品图标；Dub 组件内部保留自带图标。页面使用 Dub 默认中性色、黑色主按钮、圆角与边框。
- Vite 集成：Dub Modal 依赖 Next router context；适配层提供真实 context，将浏览器导航映射到 history/location，页面仍由 Vite 构建。`@radix-ui/react-dialog` 与 Dub 共用同一版本以关联可见标题与描述。Next 只作 Dub 兼容依赖，不运行 Next 服务。
- 许可证：Dub UI 为 AGPL-3.0，enterprise 部分商业授权。来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- react-resizable-panels：PR 会话与详情的键盘可操作分栏；中间列使用紧凑顶栏、居中会话画布与底部输入框，右侧详情可拖动调宽和收起。分支、提交和 PR 状态在详情中展示，辅助信息按需展开。小屏的导航和详情通过弹层打开。
- Zustand：持久化所选项目、PR、当前视图及左右侧栏的收起偏好，不存储凭据或服务端数据。
- TanStack Query：服务端数据、请求状态、缓存失效。SSE 通知失效；工作区轮询为断线补偿。
- React Hook Form + Zod：表单校验；后端仍独立验证同一共享契约。
- react-diff-view：按需加载代码差异，不自行实现 diff 算法和行布局。

当前没有拖拽排序功能，因此没有引入 dnd-kit。禁止引入平行 UI、图标、动画、请求缓存、表单、状态和分栏库；已有依赖可以正确表达时，必须复用其组件与行为。

## 行为约定

一个仓库对应项目，一个 PR 对应持续会话；每次提交产生新的审查轮次。历史发现正文读取轮次结果快照，已有发现的状态读取当前生命周期记录。CI 显示最近事件，未聚合多个 job。

默认“内部讨论”仅保存内部消息；选择“PR 评论”并点击“发布到 PR”后，原文写入 PR 概览或选中问题的讨论。发布审查评论也有单独确认。自动发布仅由项目配置显式开启。

消息输入框中 Enter 发送、Shift+Enter 换行；中文输入法选字期间的 Enter 不触发发送。快捷键与发送按钮共用表单校验，发送中和长按 Enter 不会重复提交。

没有预置演示数据。连接模型、仓库后，导入真实 PR。模型连接测试会实际调用配置的模型。

## 验证

`pnpm --filter @review/web typecheck` 与 `pnpm --filter @review/web build`。

## 集成注意事项

Dub 0.3.21 声明了精确的 React 19.1.3 / Next 15.5.7 peer 版本；本项目保留 React 19.3.1，并使用 Next 15.5.28 补丁版本。其传递依赖中的 Visx 和 react-day-picker 也有旧版 peer 范围，因此 pnpm 会提示版本警告。当前使用的组件已通过开发与生产构建浏览器验证；未使用 Dub 的图表和日期选择器。

Dub 发布包的入口较大，当前生产入口约 1.52 MB（gzip 473 KB），Vite 会报告 chunk 大小警告。`process.env` 在 Vite 中替换为空对象，以支持 Dub 包在导入时读取 Next 环境变量；不会把本机环境变量放入浏览器。
