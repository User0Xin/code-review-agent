# 评审详情与失败重试：独立验收记录

需求：[Issue #36](https://github.com/User0Xin/code-review-agent/issues/36)。基线 `c70dc1ca4c81469bf83259cbaf77ff4c623d1a5d`，分支 `cindy/radiant-colden`，共享工作树 `radiant-colden`。2026-11-19 直接查询远端 main 和 #35 的 mergeCommit，均为该基线。

集成目录：`C:/Users/admin/Desktop/学习/myproject/code-review-agent/code-review-agent/.cindy-worktrees/radiant-colden`。

## 缺陷与复验

以下行号为首次复现版本，修正后可能移动。均由独立评审任务实际执行验证，再交原开发任务修正。

| 编号 / 严重度 | 位置与复现 | 实际与预期 | 责任与状态 |
| --- | --- | --- | --- |
| R1 / P2 | `web/src/utils/request.ts:28,37`、`session.ts:11`；挂起重试 POST，锁定并用同口令重新解锁，打开另一任务，再释放旧 401 | 实际新会话再次被锁；应忽略旧请求，当前有效 401 仍锁定。另检查切仓、切任务失效请求的同类路径 | 前端；独立完整浏览器回归及 12 种旧 HTTP/code401 矩阵通过，关闭 |
| R2 / P2 | `MergeRequestReviewServiceImpl.java:215`；发布明确拒绝后，更新旧讨论又抛异常 | 实际 `DISCUSSION_UPDATE` 掩盖此前 `PUBLISH`；应保留已发生的发布失败说明 | 后端；保留原回归断言，独立全量通过，关闭 |
| R3 / P2 | `MergeRequestReviewServiceImpl.java:248,295`；仅发布前版本 GET 失败，并断言发现 POST 未调用 | 实际总数 null、阶段 PUBLISH；应保留已确认数量并说明 VERSION_CHECK | 后端；独立回归及 GitHub/GitLab 行评论、回退核对测试通过，关闭 |
| R4 / P2 | `StartReviewRequest.java:4`、`WorkbenchJobController.java:45`；提交 `{"number":42.5}`，让编号 42 的平台读取抛错 | 实际截断成 42 并到达平台，返回 502；应入站 400，平台零调用 | 后端；字段级严格解析，独立小数/字符串/溢出等回归通过，关闭 |

首次独立 `mvn -B -ntp clean test` 为 181 项、0 失败/错误/跳过；随后追加的三个后端缺陷回归为 3 项、3 失败。浏览器独立复跑前端脚本在旧 401 场景失败。首次证据分别为 `review-independent-clean.log`、`review-repro-backend.log`、`review-front-browser.log`（最终收存到 target，不提交日志）。这些记录不代表修正后验收通过。

规范审查未发现明确硬违规。三项非阻塞维护建议：详情读取可通过既有持久化服务封装；新记录的阶段尽量不依赖展示文案反推；GitHub 讨论标识解码约定避免两处漂移。本轮不据此增加无关重构。

## 最终验证

两端明确停止构建并冻结后，集成负责人独立执行以下检查。JDK 21.0.8，Node 25.4.0，系统 Chrome；CI 使用仓库既有 Node 22 配置。未启动 Vite，进程查询也未发现此工作树的 Vite 服务；脚本只结束自身启动的 Java、fixture 与浏览器。

| 命令 | 独立结果 | 日志（target/review-evidence 下） |
| --- | --- | --- |
| `npm --prefix web ci`、`lint:check`、`format:check`、`build` | 全部退出 0 | review-web.log |
| `mvn -B -ntp clean test` | 197 项，0 失败/错误/跳过 | review-final-clean.log |
| `mvn -B -ntp test` | 197 项，0 失败/错误/跳过 | review-final-incremental.log |
| `mvn -B -ntp -Pproduction clean package` | BUILD SUCCESS，含 197 项测试与锁文件前端构建 | review-production.log |
| `python web/tests/review_details_browser.py --browser <系统Chrome>` | 编译页面完整状态/键盘/竞态回归及 12 项旧401矩阵 PASS；此项 API 为替身 | review-final-browser-ui.log |
| `python scripts/workbench-smoke/jar_check.py` | 真实 jar 路由/资源/鉴权、旧 SQLite、去重和重启 PASS | review-jar-check.log |
| `python scripts/workbench-smoke/run.py --browser <系统Chrome> --artifacts target/review-browser` | 真实 jar 双仓库配置、Skill、成功/失败、刷新、重启和桌面/320px PASS | review-jar-browser.log |
| `python scripts/workbench-smoke/details_check.py --browser <系统Chrome> --artifacts target/review-details` | 真实 jar 详情、部分发布、同 SHA 两轮、连点重试、返回 ID、最新 SHA/配置、旧候选历史不关联、GET 乱序、两种恢复窗口 PASS | review-jar-details.log、review-jar-details-final.log |

真实详情截图 `target/review-details/details-1440.png` 与 `details-320.png` 已目视复核，长 SHA/路径/正文可换行、无横向页面溢出，HTML 片段按文本显示。浏览器脚本均退出并清理自身服务。

冻结时 177 个源码/测试/脚本/契约文件的清单指纹为 `916747b1b4ac225339a60cbfb2054fa56017c83f4395083f78b93b9fa8146fc7`，保存在 `target/review-evidence/review-tree.json`。构建后逐文件核对，产品源码无漂移；仅独立测试 fixture 补充捕获强制终止客户端时的预期断连噪声，该脚本另行完整复跑。生产 jar SHA-256 为 `273ab47225c676aad4e8288f70bbad8f881470db830413a6b3cd535d629d68f2`。日志、图片、数据库和产物不纳入提交。

本报告记录本地验收；提交 SHA、远端 PR head、CI 和评审线程由发布后回读结果另行报告。授权止于向 main 创建 PR，不合并。

平台和模型使用本机替身；不证明真实 GitHub/GitLab 权限或在线模型表现。恢复窗口涉及跨系统不确定性，不能承诺远端评论严格恰好一次。
