# 代码评审工作台

基于 [User0Xin/vue-project](https://github.com/User0Xin/vue-project/tree/046b1b5094aaa73a48616bed81192ffc1fc7a2e0) 建立，沿用 Vue 3、TypeScript、Vite、Vue Router、Pinia、vue-i18n 与其 ESLint、Prettier、EditorConfig、TypeScript 配置。移除了模板示例页面和包自身的循环依赖；依赖版本来自模板锁文件，下载地址使用公共 npm 注册表。

在 `web/` 运行 `npm ci` 和 `npm run dev`。默认打开 http://127.0.0.1:8080 ，开发代理保留 `/api` 前缀，转发至 http://127.0.0.1:8088 。可用 `REVIEW_API_ORIGIN` 指定开发后端；部署时将 `/api/` 转发给后端，并为前端路由配置 SPA 回退。

页面在 `src/pages/<页面>/index.vue`，页面私有组件在相邻 `components/`，共享组件在 `src/components/`。页面不直接调用 Axios；请求放在 `src/requests/`，与页面展示无关的逻辑放在 `src/business/`，样式、路由、状态与语言文本沿用脚手架分层。

视觉参考 [原工作台](https://github.com/User0Xin/code-review-agent/tree/5b3108263cc21726a71d60a6f378dad48e2c28d4/apps/web)，使用 Vue 实现导航和移动端菜单，无须 React 依赖。

验证：`npm run lint:check`、`npm run format:check`、`npm run build`（包含类型检查）。
