# 审查服务配置与运行

审查服务接收一个配置的 GitLab 被审查仓库的合并请求事件，读取当前 diff、spec 和编码规范，直接写回中文讨论；编码规范和缺陷发现积累后，创建待人审核的编码规范修订。服务只提交规范文件和创建修订合并请求，不批准或合并代码合并请求，也不自动合并编码规范修订。现有聊天接口独立保留。

本文描述已实现的配置与恢复机制。本地测试中的 GitLab、飞书项目和模型由替身提供；测试通过不代表已经配置真实凭据、登记线上 webhook、部署服务或执行过真实审查。

## 部署配置

配置定义见 [application.yaml](../src/main/resources/application.yaml)。需要 JDK 21、Maven、可读写的持久化目录，以及能访问 GitLab、飞书项目和模型服务的网络。

### GitLab 与模型

- `GITLAB_BASE_URL`：GitLab 实例根地址；也可填写以 `/api/v4` 结尾的 API 根地址。
- `GITLAB_PROJECT_ID`：唯一被审查仓库的数字项目编号。
- `GITLAB_TOKEN`：审查服务专用账号的访问令牌，需具备 API 及该项目的读取、评论、解决讨论、创建源分支与提交、创建合并请求权限。服务用令牌查询自己的账号，排除该账号的讨论回复。
- `OPENAI_BASE_URL`：OpenAI 兼容模型服务根地址。本项目配置会追加 `/v1/chat/completions`，这里填写到主机及端口，避免重复 `/v1`。
- `OPENAI_API_KEY`：模型服务凭据。
- `OPENAI_MODEL`：服务实际提供的模型名称。
- `REVIEW_STANDARDS_BRANCH`：规范分支，例如 `dev`。空值时使用被审查仓库的默认分支。

模型调用使用文本 JSON 契约，不依赖函数工具。每次从规范分支的固定提交重新选择并读取编码规范；合并请求中尚未生效的规范修改不会改变这次审查依据。lint 配置、依赖目录和生成目录不当作编码规范。

### 飞书项目与 spec

- `FEISHU_PROJECT_BASE_URL`：默认 `https://project.feishu.cn`。
- `FEISHU_PROJECT_KEY`：固定空间的 `project_key`。
- `FEISHU_PROJECT_PLUGIN_ID`、`FEISHU_PROJECT_PLUGIN_SECRET`：已发布并安装到该空间的插件凭据。
- `FEISHU_PROJECT_USER_KEY`：具有读取空间权限的专用账号标识。

源分支形如 `feature/7058321625-export` 时读取工作项 `7058321625`。服务自动换取并缓存短期插件令牌，再使用空间内全部工作项类型按 ID 定位，读取描述的纯文本 `doc_text`。工作项正文每次重读；空间找不到该工作项时正常继续。接口错误、凭据缺失和账号失去权限都按读取失败处理。

仓库中的 spec 文档由模型先从最新提交的 `docs/` 清单选择，再由服务校验路径并读取；编码规范文件从 spec 候选中排除。工作项和仓库文档都没有时静默跳过 spec，只在审查记录中标记。合并请求描述只能补充已有 spec。

### 阈值与等待

- `REVIEW_REVISION_N`：自上次总结以来的新发现触发数量，默认 `20`。
- `REVIEW_REVISION_M`：总结时同类发现的最低数量，默认 `3`。
- `review.diff-wait.max-attempts`：diff 等待总次数，默认 `3`。
- `review.diff-wait.interval`：重试间隔，默认 `2s`。
- `review.gitlab.request-timeout`、`review.feishu-project.request-timeout`：各自默认 `30s`。

后三组配置可通过启动参数覆盖，例如 `--review.diff-wait.max-attempts=5 --review.diff-wait.interval=3s`。N 和 M 小于 1 时按 1 处理。

每次审查结束检查是否需要修订。只有编码规范类和缺陷类发现参与；达到触发条件后，逐条读取讨论中除服务账号之外的全部回复，按最终态度判定误报并保存理由。误报排除后仍需达到阈值。仅把讨论标成已解决不代表误报；不足 M 的类别继续积累。

## 构建与启动

在仓库根目录执行：

```powershell
mvn clean test
mvn test
mvn -DskipTests package
```

前两条分别验证干净构建和增量构建，与 CI 保持一致。测试通过 webhook 观察讨论、持久化记录和修订行为；HTTP 传输测试使用本机替身，不请求真实 GitLab 或飞书项目，也不调用真实模型。

运行前从部署环境或秘密管理服务注入凭据，并设置持久化文件路径。示例仅展示非敏感参数：

```powershell
$env:REVIEW_DATABASE_PATH = 'D:/service-data/review-records.db'
$env:GITLAB_BASE_URL = 'https://gitlab.example.com'
$env:GITLAB_PROJECT_ID = '123'
$env:REVIEW_STANDARDS_BRANCH = 'dev'
java -jar target/code-review-agent-0.0.1-SNAPSHOT.jar
```

数据库父目录需预先存在且可写。默认监听端口为 `8088`，可用 `--server.port=8088` 等 Spring Boot 参数覆盖。示例没有提供令牌及模型、飞书项目凭据，需要按上文补齐。

## 配置 webhook

在被审查仓库登记合并请求事件，URL 指向：

```text
POST https://<服务入口>/gitlab/webhooks/merge-requests
X-Gitlab-Event: Merge Request Hook
Content-Type: application/json
```

服务处理 `object_kind=merge_request` 且 `action=open`，以及带非空 `oldrev` 的 `action=update`。只改标题、描述或指派人等没有新代码的更新不触发审查。配置了项目编号后，其他项目的事件被忽略。

服务按 webhook 的 `last_commit.id` 核对 diff，并用 `head_sha`、`base_sha`、`start_sha` 三个提交标识固定本次 diff version；模型审查后再次核对完整版本，即使源分支提交未变、目标分支改变导致 diff 改变，也会停止写回。过大的或被截断的 diff 按失败处理。可挂行的发现写成行讨论；GitLab 明确拒绝行位置时回退为单独概览讨论。其他写回失败保留已经成功写回的发现，并说明未写上的数量。

当前 webhook 同步处理，入口超时应覆盖文件读取和模型调用耗时。HTTP 200 表示事件已处理，不表示审查一定成功；结果以审查记录和合并请求讨论为准。当前应用端点未校验 `X-Gitlab-Token`，部署入口应通过内网或反向代理访问控制限制调用方。

## 三份随包发布的指令

- [审查指令](../src/main/resources/skills/review/SKILL.md)：分别选择 spec、选择编码规范、执行审查，规定三种类别、中文评论、历史发现匹配和 Fowler 坏味道提示。编码规范优先于通用提示。
- [误报判定指令](../src/main/resources/skills/false-positive/SKILL.md)：每条候选发现独立判定，阅读全部外部回复的最终态度，输出布尔结论和一句理由。
- [总结指令](../src/main/resources/skills/summary/SKILL.md)：总结时归类，输出原文精确替换片段、依据的发现 ID 和理由。新文本跟随原文件语言，看不出时用中文。

服务将对应指令全文作为系统消息注入；模型输出必须符合固定 JSON 结构。服务再次检查路径、字段、发现资格、阈值和原文唯一性。只修改已有规范文件的局部段落，不新建规范文件。对不上的替换保持未总结，合格替换可以继续提交；修订标题和按文件组织的描述为中文。

指令位于 `src/main/resources/skills/`，随 JAR 发布；修改后需要重新构建并部署。

## SQLite 持久化与中断恢复

`REVIEW_DATABASE_PATH` 默认是当前工作目录下的 `./review-records.db`。部署时应使用固定绝对路径并挂载持久化卷；重新部署继续使用同一个文件。连接池限制为单连接。启动时创建缺失表，并在旧数据库上补充新字段，保留已有记录。

数据库保存审查记录、发现、当前失败说明索引、编码规范修订和总结检查点。审查记录包含触发原因、提交、spec 是否跳过、实际读入的文档路径、成败、失败阶段和失败说明编号；总结失败另记 `summary_error`。发现保留讨论及代码位置、类别、引用、正文、提交、误报结论与理由、所属修订。数据库不保存完整提示词或模型原始回复。

审查失败时尝试只保留最新一条中文概览说明，正文只有原因和时间；后续成功时删除。GitLab 不能写入或清理说明时，通知错误留在审查记录，不抹掉已经保存的发现。模型 SDK 错误在边界转为固定原因，避免把请求或原始回复带入记录。

总结或创建修订失败不另发通知，下一次审查结束重试，没有独立定时重试任务。创建远端修订前先保存 `PREPARING` 提案，包括固定基线提交、源分支、文件内容和发现 ID。重启后先用同一源分支寻找已经创建的修订；远端已提交但尚未创建合并请求时核对文件并继续创建，避免重复提交或打开第二份修订。尚未打开的提案在重试前重判相关回复，已被开发者否定的依据不能继续发布。

已有一份打开的修订时，其他发现继续积累。修订被使用的发现记录所属修订并停止计数；即使修订未合并便关闭，也不放回待总结。只有后续新产生的发现重新积累。服务不会批准或合并这些修订。

## 设计与实现索引

[设计地图 #1](https://github.com/User0Xin/code-review-agent/issues/1) 的 13 项设计子 issue 已完成，设计结果为 [spec #16](https://github.com/User0Xin/code-review-agent/issues/16)。该 spec 的实现子 issue #17–#24 已完成，并进入 `main`；实现收口提交为 [31f96b3](https://github.com/User0Xin/code-review-agent/commit/31f96b3babaea98e8e1ce377703f5463843bbee9)。

2026-10-27 完成核验时，95 项测试通过 `mvn clean test` 和 `mvn test`；`mvn -DskipTests package` 成功，生成的 JAR 包含审查、误报判定、总结三份指令。测试覆盖本机 HTTP 替身、SQLite 旧库升级及重启恢复。真实凭据配置、线上 webhook 登记、部署和第一次真实审查未在此次实现中执行。
