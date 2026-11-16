# 代码评审工作台

工作台管理**被审查仓库**。这里的 spec 路径指该仓库里的文件，与本项目用 GitHub Issues 管理开发需求是两件事。

## 启动与解锁

1. 使用 JDK 21，配置可持久化的 `REVIEW_DATABASE_PATH`（父目录须已存在）、模型服务的 `OPENAI_*` 和需要使用的平台凭据。GitHub 使用 `REVIEW_GITHUB_TOKEN`，GitLab 使用 `GITLAB_BASE_URL` 和 `GITLAB_TOKEN`。具体权限见[审查服务说明](review-service.md)。
2. 在服务端设置独立的 `REVIEW_WORKBENCH_TOKEN`，然后从仓库根目录运行 `mvn spring-boot:run`。本地默认端口为 8088。未配置管理口令时，工作台 API 返回 503；口令错误返回 401。不要把平台令牌或模型密钥填入浏览器。
3. 在另一个终端进入 `web/`，首次执行 `npm ci`，然后运行 `npm run dev`。打开 <http://127.0.0.1:8080>，使用工作台管理口令解锁。前端只将管理口令保留在当前标签页的会话存储中，可主动退出。生产部署请使用 HTTPS，将前端和 `/api/` 放在同一源，API 转发到后端；不要把口令打包进静态文件。

## 设置仓库与导入 Skill

- 添加 GitHub 或已配置 GitLab 实例的 HTTPS 仓库主页链接。服务端使用自己的平台 API 核对仓库身份，保存平台及原生数字 ID。不能用 PR/MR 页面或任意 API 地址代替仓库链接。
- 每行填一个**仓库根目录相对路径**，可指向文档或目录（如 `CODING_STANDARD.md`、`docs/features/`）。服务对规范分支的固定 SHA 取规范，对该次 PR/MR 的 head SHA 取 spec。目录包含其下匹配的文档，规范文件不会重复作为 spec。任一类留空就沿用该类的自动选择逻辑；显式位置没有匹配到文件会使该轮失败。
- 选择环节再导入本地 `SKILL.md`：`review` 供文档选择与评审使用，`false-positive` 和 `summary` 供 GitLab 规范修订使用。接受非空 UTF-8 Markdown，最大 64 KiB。一个仓库的一个环节只保留一个自定义版本，再次导入覆盖该环节；关闭自定义版本后使用内置指令，删除后也恢复内置。为同一环节定制时可从内置文本开始，保留该环节要求的输出结构。
- 配置、已导入的内容及启用状态保存在服务端 SQLite 中。重启使用同一个数据库文件即可恢复；它们不会随浏览器退出而删除。
