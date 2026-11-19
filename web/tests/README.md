# 评审详情与重试：前端浏览器回归

对应 [Issue #36](https://github.com/User0Xin/code-review-agent/issues/36) 和 [接口契约](../../docs/review-job-details-spec.md)。

```sh
npm --prefix web ci
npm --prefix web run lint:check
npm --prefix web run format:check
npm --prefix web run build
python web/tests/review_details_browser.py --help
python web/tests/review_details_browser.py --browser "C:/Program Files/Google/Chrome/Application/chrome.exe"
```

需要 Python 与 Playwright。已经安装 Playwright Chromium 时可省略 `--browser`。脚本使用 `web/dist` 的实际构建页面，启动自己的随机端口 HTTP 服务和隔离的无头 Chromium 会话，结束时关闭两者。**不启动 Vite，不构建或启动 Java，不占用 Maven target，不使用个人浏览器登录状态。**

API 由 Playwright 请求拦截提供 v1 契约替身。可以暂停单个请求并在切换页面后释放，以确定性验证旧响应隔离。该检查不能代替生产 jar、SQLite 或平台写回集成验收；最终集成由 `scripts/workbench-smoke/` 的真实 jar 脚本验证。

覆盖内容：

- 加载、排队、运行、成功且精确 0、成功有发现、失败且精确 0、部分写回失败、历史未关联、结果缺失、中断。
- spec 三态、依据路径的 null/空数组/非空语义、完整 SHA、左右侧行号、多行正文与引用、HTML 按文本显示。
- GitHub 评论与 GitLab 嵌套路径 note 链接；拒绝不可信链接；响应中的仓库和任务 ID 不匹配时不展示数据。
- 详情首次 404/500，刷新 500 保留并标明旧数据，刷新 404 清除数据；当前会话 401 回到解锁。
- 重试连点只有一次 POST；返回新任务、已有活动任务、冷却内终态任务均按返回 ID 定位；activeJobId 仅导航。
- 重试 409/502/503 保留原详情；迟到 GET/POST 在切任务、切仓库、退出后不污染当前数据、错误、通知、焦点或任务定位。
- 旧 POST 完成不能解锁新任务仍在提交的按钮；同一令牌退出再解锁后，旧 401 不能使新会话失效。
- R1 鉴权竞态矩阵：GET/POST × 切任务/切仓/同令牌重新解锁 × HTTP 401/HTTP 200 且 code=401，共 12 种迟到响应；旧响应不能锁定新上下文，每种场景随后再确认新鲜当前 GET/POST 401 仍锁定。可加 `--auth-only` 单独运行。
- 键盘打开、详情标题焦点、Tab、Escape 返回触发按钮；1440px 与 320px；页面无横向溢出。

截图默认写入已忽略的 `web/test-artifacts/review-details/`，可通过 `--artifacts` 覆盖。失败时额外写入 `failure.png` 与 `failure.txt`；后续成功不会删除旧失败证据，应以该次命令结果为准。

固定测试标识：列表行 `review-job-{id}`，详情按钮 `review-details-{id}`；详情容器 `review-detail`（附 `data-job-id` 与 `data-repository-id`），标题 `review-detail-title`，读取状态 `review-detail-loading` / `review-detail-error`；刷新、关闭、重试分别为 `review-detail-refresh` / `review-detail-close` / `review-retry`；活动任务入口 `review-active-job`，提交反馈 `review-retry-notice` / `review-retry-error`；发现列表 `review-findings` 与 `review-finding-{id}`。

## 2026-11-19 前端交接

前端实现和上述检查已通过，可交独立评审。基线 `c70dc1ca4c81469bf83259cbaf77ff4c623d1a5d`，共享分支 `cindy/radiant-colden`，前端改动均在 `web/`，未提交。执行环境：Node 25.4.0、npm 11.8.0、Python 3.13.9、系统 Chrome。生产要求的 Node 22 环境尚未在本任务验证。

补充回归曾复现旧 401 错误使重新解锁的同令牌会话失效，已在 `utils/session.ts` 与 `utils/request.ts` 加入会话代次并复验通过。首次浏览器运行的默认 Playwright Chromium 不存在，改用上述系统 Chrome 命令；静态测试服务器已固定 JavaScript MIME，避免 Windows 注册表 MIME 配置导致模块不加载。

独立评审 R1 补充修正：会话代次只能保护重新解锁；同会话内切任务/切仓的旧 401 仍可能在页面丢弃响应前由共享请求层注销。最小 `--auth-only` 回归先复现 `FAIL R1: GET-task-http401`。现将评审请求的上下文有效性回调传入请求层，在成功响应和 HTTP 错误分支触发退出前统一检查，保护详情 GET、列表 GET、手动/重试 POST。修正后的完整回归和 12 项矩阵已通过；独立负责人需针对最终构建重新执行，不能沿用本记录作为独立验收结果。

本任务未启动 Vite、未执行 Maven、未运行生产 jar，真实后端联调及真实 jar 浏览器验收仍由独立评审任务统一完成。平台与模型请求次数、迁移与重启持久化、跨平台真实 ID 隔离不属于本替身测试的证明范围。
