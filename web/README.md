# 代码评审工作台

基于 [User0Xin/vue-project](https://github.com/User0Xin/vue-project/tree/046b1b5094aaa73a48616bed81192ffc1fc7a2e0) 建立，沿用 Vue 3、TypeScript、Vite、Vue Router、Pinia、vue-i18n 与其 ESLint、Prettier、EditorConfig、TypeScript 配置。移除了模板示例页面和包自身的循环依赖；依赖版本来自模板锁文件，下载地址使用公共 npm 注册表。

在 `web/` 运行 `npm ci` 和 `npm run dev`。默认打开 http://127.0.0.1:8080 ，开发代理保留 `/api` 前缀，转发至 http://127.0.0.1:8088 。可用 `REVIEW_API_ORIGIN` 指定开发后端。

从仓库根目录运行 `npm ci --prefix web`、`npm run build --prefix web`，生成标准 Vite 生产产物 `web/dist/`；构建同时执行 TypeScript 检查。单包构建和 Spring 启动方式见 [工作台部署文档](../docs/workbench.md)。`node_modules/` 和 `dist/` 不提交到 Git。

生产资源按站点根 `/` 部署，页面使用 `/assets/` 下的构建资源及 `/review-mark.svg`，管理请求使用同源 `/api/`。现有 history 路由为 `/repositories`、`/reviews`，首页 `/` 转到仓库配置；服务端需要支持这些页面直达及刷新。未知 API 和缺失静态资源应返回错误，不能套用首页回退。

页面在 `src/pages/<页面>/index.vue`，页面私有组件在相邻 `components/`，共享组件在 `src/components/`。页面不直接调用 Axios；请求放在 `src/requests/`，与页面展示无关的逻辑放在 `src/business/`，样式、路由、状态与语言文本沿用脚手架分层。

仓库和 Skill API 需要后端设置独立的 `REVIEW_WORKBENCH_TOKEN`。打开工作台后输入这个令牌即可；令牌只保存在当前标签页的 sessionStorage，点击“锁定工作台”清除。不要输入 GitHub/GitLab 平台令牌。

在“评审任务”选择仓库并填写 PR（GitHub）或 MR（GitLab）编号，提交后列表展示服务端记录、触发来源和执行结果。活动任务自动更新；窗口隐藏时暂停轮询，返回页面可手动刷新。

视觉参考 [原工作台](https://github.com/User0Xin/code-review-agent/tree/5b3108263cc21726a71d60a6f378dad48e2c28d4/apps/web)，使用 Vue 实现导航和移动端菜单，无须 React 依赖。

验证：`npm run lint:check`、`npm run format:check`、`npm run build`（包含类型检查）。
