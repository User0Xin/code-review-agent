# 工作台单包交付独立验收（2026-11-18）

需求来源：产品经理《工作台集成交付需求》及《工作台集成交接》（2026-11-18）。本轮仅主线集成与单 jar 交付，不包含结果详情新功能。独立复验没有使用旧 PR #33 的测试数字。

## 快照与环境

集成分支 `codex/workbench-delivery`，目标 `main`。基线 `549da2944bb23419132035dce6d1214a95e46b71`，功能源 `5cd30e47f6c656a2d77e2df7facba5bde65afc7f`。审查涵盖已暂存、未暂存、新文件和 12 个冲突解决；保留主线完整 diff 引用保护、已删除讨论处理、草稿去重和 GitHub 负数存储命名空间。

Windows；JDK 21.0.8、Maven 3.9.2、Node 25.4.0、npm 11.8.0、Python Playwright + 系统 Chrome。CI 另用 Ubuntu/JDK 21/Node 22。

将最终源码导出到 `target/review-clean/`，不包含 node_modules、dist、数据库或旧 target，从该干净源码执行完整构建；逐文件 SHA256 保存在 `target/review-source-manifest.json`，发布前核对运行源码未漂移。以下生产 jar 及各检查属于同一源码快照，包含最终 320px 修复。

产物 `target/review-clean/target/code-review-agent-0.0.1-SNAPSHOT.jar`，SHA256：

`5952CFC2ED7D093B41F43D6E61E638448BA958178A602F84B2B0F8145B2279AF`

两端开发交接均已收齐，Java明确冻结，前端开发与自测完成并交回复核。前端交接中基于旧jar列出的待验收项，已由下列最终快照检查覆盖。确认本机无Vite进程后再次执行追加浏览器检查，退出码0，见 `target/review-no-vite.log`。最终jar内7个静态文件与独立前端build产物逐字节一致。

## 实际执行结果

以下常规命令均在 `target/review-clean/` 执行，证据日志保留在外层 target。

| 检查 | 独立结果 | 证据 |
| --- | --- | --- |
| `mvn -B -ntp -Pproduction clean package` | PASS；npm ci、类型检查、生产资源、129 项测试与可执行 jar | `target/review-production.log` |
| `mvn -B -ntp test` | 129 项，0 失败/错误/跳过 | `target/review-backend.log`、快照的 surefire-reports |
| `npm run lint:check --prefix web` | PASS | `target/review-lint.log` |
| `npm run format:check --prefix web` | PASS | `target/review-format.log` |
| `npm run build --prefix web` | PASS；包含 vue-tsc | `target/review-web-build.log` |
| `python scripts/workbench-smoke/jar_check.py --artifacts ../review-jar-check` | PASS；实际 jar HTTP、旧库升级、三次进程启动 | `target/review-jar-check.log`、`target/review-jar-check/` |
| `python scripts/workbench-smoke/run.py --browser "C:/Program Files/Google/Chrome/Application/chrome.exe" --artifacts ../review-browser` | PASS；实际 jar 页面、Spring API、SQLite、浏览器刷新与重启 | `target/review-browser.log`、四张桌面/320px截图 |
| 独立追加浏览器脚本 `target/review-extra.py`，传入上述最终 jar | PASS；真实401/503/409、三秒提示、签名webhook去重、同源、独立标签页及锁定 | `target/review-final-extra.log`、`target/review-final-extra/` |

## 验收矩阵

| 验收项 | 已核对的实际行为 |
| --- | --- |
| 单包启动、资源与 SPA | 验收只启动 jar，不启动 Vite；首页、两个页面及尾斜杠 HTTP 直达；浏览器两页进入/刷新和 JS/CSS/SVG 正常 |
| 边界与鉴权 | 未授权 API 401、未配置口令503；授权后未知API、资源、webhook404；页面POST405；无效webhook403，未返回首页伪成功 |
| 双仓库隔离 | 浏览器分别设置路径、导入不同review Skill、触发成功/失败任务；刷新、切换仓库不串用；模型fixture验证实际文档和指令入参 |
| Skill | 导入、禁用、恢复内置及另一仓保持自定义；同库重启后有效指令保持 |
| 重试、去重、409 | 终态后三秒内返回旧任务且浏览器提示未新建；运行中手动/有效签名webhook复用、成功webhook重投不新增；真实缺少diff引用返回409且不新增任务 |
| 会话与密钥 | 管理请求均同源；401清空当前标签页口令；刷新保留会话、新标签页锁定、主动锁定清除；浏览器未使用平台和模型密钥 |
| SQLite | 老schema历史审查/发现不丢；补齐字段；两仓配置、Skill及成功/失败任务重启保留；QUEUED/RUNNING变为中断失败，不重放 |
| GitLab | 全量测试实际执行 WorkbenchGitlabManualTest（本机HTTP平台fixture）与 RegisteredGitlabWebhookTest（控制器自动化），保留旧webhook回归 |
| 320px | 两页无横向溢出；已目视复核截图，当前页名单行，表单和任务可读；浏览器无pageerror |
| 开发模式 | Vite入口与 /api 代理配置保留；生产请求不依赖Vite |

## 缺陷关闭与审查判断

1. **P1 已关闭**：`jar_check.py` 将首页请求声明为只接受JSON，真实jar返回406，导致探活超时并阻断CI。已反馈Java原任务；开发改为 `Accept: */*`，仍对页面内容/API JSON分别断言。独立最终 `jar_check.py` 全流程PASS。
2. **320px排版问题已关闭**：前端发现顶部面包屑挤压断字，隐藏窄屏重复前缀。独立最终jar两页截图确认修复。
3. **Windows验收清理问题已关闭**：开发发现SQLite上下文管理器未关闭句柄，改为显式closing和提交。独立jar_check退出码0且临时目录清理成功。
4. 核对了“失败webhook超过三秒可重试”的疑点：验收约束为活动版本与成功版本去重，没有禁止失败重投；503恢复后的再次受理不属于已确认缺陷，未改变现有语义。

## 限制与发布

GitHub/OpenAI浏览器流程和GitLab协议使用本机fixture；未验证真实平台权限、在线模型表现、HTTPS反向代理、多进程或非根路径部署。服务仍按单应用进程共享一个SQLite文件部署。运行只需Java21及服务端配置；构建用 `mvn -B -ntp -Pproduction clean package`，启动用 `java -jar code-review-agent-0.0.1-SNAPSHOT.jar`，详见 [部署文档](workbench.md)。

本报告记录本地独立验收；远端SHA、PR目标分支与CI结果在发布后单独回报。未授权合并main。生成物、数据库、截图、日志和工具临时JSON不纳入提交。
