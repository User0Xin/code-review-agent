# 工作台单包交付：后端交接（2026-11-18）

## 工作区与状态

- 工作目录：`C:/Users/admin/Desktop/学习/myproject/code-review-agent/code-review-agent/.cindy-worktrees/amazing-panini`
- 分支：`codex/workbench-delivery`；HEAD `549da2944bb23419132035dce6d1214a95e46b71`，MERGE_HEAD `5cd30e47f6c656a2d77e2df7facba5bde65afc7f`。
- 12 个冲突已逐项对照并解决，已 `git add` 标记；保留待提交合并。没有 commit、push、建 PR、merge main、切分支或操作原工作树。
- 后端交付停止修改；最终完整快照、前端冻结后的生产重打包、独立浏览器验收及发布由 review 工程师负责。不要覆盖其 `docs/workbench-delivery-review.md`。

## 冲突与行为保留

解决 `docs/review-service.md`、两个 webhook Controller、`GithubApi`、`GithubClient`、`RestGitlabClient`、`MergeRequestReviewService`、`RepositoryClient`、`MergeRequestReviewServiceImpl`、`application.yaml`、`GithubWebhookControllerTest`、`StaleReviewWriteTest`。

工作台接口增加固定仓库适配器、登记任务与返回真实审查记录，旧单仓库 webhook 路径仍保留。核对了主线的完整 diff 引用变化保护、已删除讨论处理、重复草稿去重、失败通知维护、GitHub 负数存储命名空间。GitLab 的 open/update+oldrev 判断迁到 DTO 的 `startsReview()`，条件不变；草稿在调用 `postDrafts` 前去重，以便工作台统计真实发布数量。主线 `CODING_STANDARD.md` 和领域约定未改。

## 新增交付

- `pom.xml`：`production` profile 顺序执行 npm ci、npm run build，将 `web/dist` 放入 `BOOT-INF/classes/static`；普通后端测试无 Node 依赖，Windows 使用 npm.cmd。
- `WorkbenchPagesConfiguration`：仅 `/repositories`、`/reviews` 及各自尾斜杠转发 index；首页用 Spring 静态 welcome page。API、webhook 和缺失资源不回退首页。
- `WorkbenchPagesTest`：页面直达、错误方法、未知页面/资源/API/webhook、API 授权边界。
- `DatabaseRestartWebhookTest`：在既有老 schema 升级测试上增加仓库、Skill、终态任务保留以及 QUEUED/RUNNING 重启中断断言。
- `scripts/workbench-smoke/jar_check.py`：标准库 HTTP + 实际 jar + SQLite + 本机 GitHub/OpenAI fixture；覆盖无配置/有配置/同库重启三次独立进程。
- `scripts/workbench-smoke/run.py`：改为只启动 jar，同源页面/API，320px 窄屏；不再启动 Vite。
- `.github/workflows/ci.yml`：新增生产 clean package 和真实 jar_check；保留前端独立 lint/format/build。
- `docs/workbench.md`、smoke README：构建、Java-only 启动、环境变量、生产入口与数据库升级说明。

未修改 `web/`。数据库无需额外破坏性迁移：原 `ReviewDatabaseMigration` 补齐旧审查/发现列，新工作台表幂等创建；不重建历史表。部署使用单进程、同一可写 SQLite 文件；升级前备份。历史 webhook 记录不会自动转换为仓库配置。

## 本轮实际验证

执行环境：Windows，JDK **21.0.8**，Maven **3.9.2**，Node **25.4.0**，npm **11.8.0**。CI 配置使用 Node 22；本机未声称已验证 Node 22。

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.8'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn -B -ntp test
mvn -B -ntp -Pproduction clean package
python scripts/workbench-smoke/jar_check.py
```

| 检查 | 结果与证据 |
| --- | --- |
| 冲突解决后原回归 | 127 项，0 failure/error/skipped |
| 新增覆盖后 mvn test | **129 项，0 failure/error/skipped**；`target/backend-test.log` |
| production clean package | **成功，129 项测试通过**；自动 npm ci + 类型检查/Vite build；`target/production-build.log` |
| 实际 jar | **PASS**；`target/jar-check/result.txt` 及 unconfigured/first/restart.log |
| GitLab | 本轮执行 `WorkbenchGitlabManualTest`（本机 HTTP fixture）、`RegisteredGitlabWebhookTest`、`MergeRequestWebhookTest`；见 surefire 报告 |
| 旧 schema | 保留历史 SUCCESS 审查和发现，补齐字段；新建两仓各自配置/Skill、成功/失败任务；同库重启保留且未结束任务标记失败 |
| API/页面边界 | 首页与两页面返回同一 index，构建 JS/CSS/SVG 可读；未知 API/静态资源/webhook 404；管理口令错误401、缺配置503；页面POST405；未签名webhook403 |
| 去重 | 终态后三秒内重复手动返回原任务；签名正确的成功版本 GitHub webhook 不增加任务 |

产物：`target/code-review-agent-0.0.1-SNAPSHOT.jar`。本次后端验证包 SHA256：`7DC3D20B369A388E1CE0D9154C233A5165626394FB70E43C39967F9A62BCF6C2`。

此包生成于前端最终 320px 面包屑样式修改前；**最终发布必须在前端冻结后重新运行 production clean package 与验证，不能把本 SHA 当作最终发布内容**。clean 会清理 target 证据，review 可先复制需要留存的日志。所有生成文件不提交。

## 已修复的验收脚本缺陷

1. reviewer 反馈的 P1：原探活发送 `Accept: application/json` 请求 HTML 首页，得到406。改为 `*/*`，最终 jar_check 全流程 PASS，请独立复验。
2. Windows 临时数据库清理：Python sqlite connection 上下文管理器只提交事务、不关闭句柄；改用 `contextlib.closing` 并显式提交新增中断任务，重跑 PASS。

本任务没有运行浏览器验收或真实 GitHub/GitLab/在线模型联调；不将 HTTP 检查冒充浏览器通过。前端 lint、format、最终视觉和独立浏览器结果由对应工程师交付。后端无已知未解决实现阻断项，等待最终独立复验。
