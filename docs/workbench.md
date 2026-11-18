# 代码评审工作台

工作台管理**被审查仓库**。这里的 spec 路径指该仓库里的文件，与本项目用 GitHub Issues 管理开发需求是两件事。

## 单包生产构建与部署

构建机准备 JDK 21、Maven、Node.js 22 和 npm。在干净 checkout 的仓库根目录执行：

```sh
mvn -B -ntp -Pproduction clean package
```

`production` profile 按顺序执行 `web/` 下的 `npm ci`（使用 `package-lock.json`）、`npm run build`（含类型检查），将 `web/dist` 原样复制到 jar 的 `BOOT-INF/classes/static/`，运行后端测试并生成 `target/code-review-agent-0.0.1-SNAPSHOT.jar`。Windows 自动使用 `npm.cmd`。普通 `mvn test` 不需要 Node；普通 `mvn package` 不保证包含前端，请始终用上述生产命令交付。构建失败时不要沿用旧 jar。不要提交 `dist`、`node_modules`、`target` 或运行数据库。

运行机只需要 Java 21 和该 jar，不需要 Node、npm、Vite 或源码。先设置下列服务端环境变量，然后启动：

```sh
java -jar code-review-agent-0.0.1-SNAPSHOT.jar
```

| 配置 | 用途 |
| --- | --- |
| `REVIEW_WORKBENCH_TOKEN` | 独立管理口令；未配置时 API 503，错误口令 401 |
| `REVIEW_DATABASE_PATH` | SQLite 文件绝对路径，父目录须已存在且可写；默认相对运行目录的 `./review-records.db` |
| `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL` | 服务端模型配置，地址不重复加 `/v1` |
| `REVIEW_GITHUB_TOKEN` | GitHub 出站访问令牌 |
| `GITLAB_BASE_URL`、`GITLAB_TOKEN` | GitLab 实例与出站令牌 |
| `REVIEW_GITHUB_WEBHOOK_SECRET`、`REVIEW_GITLAB_WEBHOOK_SECRET` | 使用对应入站 webhook 时配置的校验密钥 |

默认入口 <http://127.0.0.1:8088>，端口可用 `--server.port=8088` 覆盖。首页、`/repositories`、`/reviews`（含末尾 `/`）直接进入和刷新均由同一个 jar 提供，页面请求同源 `/api/`。只有已知页面转发到 `index.html`；未知页面、缺失静态资源、授权后的未知 API 和未知 webhook 保持 404，未授权 API 仍为 401/503。已知 webhook 保留各自校验机制，不使用管理口令替代签名。

生产入口通过 HTTPS 反向代理同一个 Spring 服务即可，不要添加全局 SPA rewrite；否则代理可能把 API/静态文件错误改成首页。浏览器只输入管理口令，平台令牌与模型密钥留在服务端。

## SQLite 升级与恢复

升级前停掉旧进程并备份数据库（存在 WAL 文件时一起保留），新 jar 指向同一个 `REVIEW_DATABASE_PATH`。启动建表脚本只创建缺失表和索引；`ReviewDatabaseMigration` 按列检查旧表并补齐缺失字段，不删除或重建历史审查与发现。工作台的仓库、Skill 和任务表也采用 `CREATE TABLE IF NOT EXISTS`。同一 SQLite 文件仅供单个应用进程使用，不要同时运行新旧版本。

重启保留仓库配置、Skill 及成功/失败任务；`QUEUED`/`RUNNING` 标记为失败中断，不自动重放。旧版本只有 webhook 记录的数据库不会凭历史项目编号自动生成仓库配置，需在工作台登记；旧审查数据仍保留。后端 `DatabaseRestartWebhookTest` 和下方真实 jar 检查覆盖老 schema 数据保留与重启。

```sh
python scripts/workbench-smoke/jar_check.py
```

该检查只用 Python 标准库，启动并停止三个 jar 进程，验证页面/资源/鉴权/webhook 边界、两个模拟仓库、旧数据库升级与重启；不启动 Vite。日志写入 `target/jar-check/`。完整浏览器流程另见下方验收文档。

## 独立前端开发与解锁

1. 使用 JDK 21，配置可持久化的 `REVIEW_DATABASE_PATH`（父目录须已存在）、模型服务的 `OPENAI_*` 和需要使用的平台凭据。GitHub 使用 `REVIEW_GITHUB_TOKEN`，GitLab 使用 `GITLAB_BASE_URL` 和 `GITLAB_TOKEN`。具体权限见[审查服务说明](review-service.md)。
2. 在服务端设置独立的 `REVIEW_WORKBENCH_TOKEN`，然后从仓库根目录运行 `mvn spring-boot:run`。本地默认端口为 8088。未配置管理口令时，工作台 API 返回 503；口令错误返回 401。不要把平台令牌或模型密钥填入浏览器。
3. 在另一个终端进入 `web/`，首次执行 `npm ci`，然后运行 `npm run dev`。打开 <http://127.0.0.1:8080>，使用工作台管理口令解锁。前端只将管理口令保留在当前标签页的会话存储中，可主动退出。此 Vite 流程只用于开发，生产使用上面的 jar 入口；不要把口令打包进静态文件。

## 设置仓库与导入 Skill

- 添加 GitHub 或已配置 GitLab 实例的 HTTPS 仓库主页链接。服务端使用自己的平台 API 核对仓库身份，保存平台及原生数字 ID。不能用 PR/MR 页面或任意 API 地址代替仓库链接。
- 每行填一个**仓库根目录相对路径**，可指向文档或目录（如 `CODING_STANDARD.md`、`docs/features/`）。服务对规范分支的固定 SHA 取规范，对该次 PR/MR 的 head SHA 取 spec。目录包含其下匹配的文档，规范文件不会重复作为 spec。任一类留空就沿用该类的自动选择逻辑；显式位置没有匹配到文件会使该轮失败。
- 选择环节再导入本地 `SKILL.md`：`review` 供文档选择与评审使用，`false-positive` 和 `summary` 供 GitLab 规范修订使用。接受非空 UTF-8 Markdown，最大 64 KiB。一个仓库的一个环节只保留一个自定义版本，再次导入覆盖该环节；关闭自定义版本后使用内置指令，删除后也恢复内置。为同一环节定制时可从内置文本开始，保留该环节要求的输出结构。
- 配置、已导入的内容及启用状态保存在服务端 SQLite 中。重启使用同一个数据库文件即可恢复；它们不会随浏览器退出而删除。

## 手动开始评审、查看结果

选中一个已登记仓库，打开评审任务，输入该仓库里的 PR（GitHub）或 MR（GitLab）数字编号，点击「触发评审」。后端用平台 API 读取这次变更的说明和完整 diff 引用；编号不存在、不可读或版本未就绪时，界面会显示原因。HTTP 202 仅表示受理。队列与评审结果保存在 SQLite；打开列表或刷新会显示所选仓库最近 100 次任务的状态、版本、成功摘要或失败原因。实际评论仍在对应的 PR/MR 上。

手动和 webhook 共用同一执行路径：同一仓库、编号和整组 diff 版本同时到达时只运行一次。已成功版本的 webhook 重投不会再次审查；连续点击会返回原任务。需要在同一提交应用修改后的配置或重试失败时，可在前一任务结束三秒后手动发起。服务重启时，先前仍在排队或运行的任务标记为中断，不会盲目重发可能已经写回的评论。请先核对远端再重试。

对已登记仓库启用自动触发：GitHub 仍用 `REVIEW_GITHUB_WEBHOOK_SECRET` 校验签名并核对名称和仓库 ID；GitLab 请同时设置服务端 `REVIEW_GITLAB_WEBHOOK_SECRET` 与 webhook 的 Secret token。只想手动评审时，无需配置入站 webhook。部署使用单个应用进程配一个 SQLite 文件。

无需真实平台或模型凭据的完整复验步骤，见[本机浏览器验收](../scripts/workbench-smoke/README.md)。
