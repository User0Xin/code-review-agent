# 评审详情与失败重试

- 状态：需求与接口契约 v1；独立验收结果见[验收记录](review-job-details-review.md)。
- 权威需求：[Issue #36：评审详情与失败重试](https://github.com/User0Xin/code-review-agent/issues/36)。Issue 正文引用本契约文件并载有可独立阅读的字段、行为和验收要求。两处须同步，变更契约先由产品经理协调两端及集成负责人。
- 基线：`origin/main`，`c70dc1ca4c81469bf83259cbaf77ff4c623d1a5d`，2026-11-19 已 fetch 核实；包含已合入的 #35。
- 集成分支：`cindy/radiant-colden`。沿用本任务宿主创建的独立工作树；两端使用同一目录，按目录分工。
- 相关说明：[工作台](workbench.md)、[审查服务](review-service.md)、[编码规范](../CODING_STANDARD.md)、[本机浏览器验收](../scripts/workbench-smoke/README.md)。

## 1. 用户结果与范围

用户能从现有评审记录打开一个任务，知道它针对哪个被审查仓库、哪个合并请求、哪一组 diff 版本，实际采用哪些 spec 和编码规范，本轮实际发布了哪些发现，或在什么阶段失败。失败和中断任务可手动重试，并定位服务端实际返回的任务。

沿用 UI 中 GitHub PR、GitLab MR 的平台名称。本文“任务”指 `workbench_job`，“审查记录”指 `review_record`，“发现”仅指已经写回评论的问题记录，不包括模型草稿、失败通知和此前轮次的发现。

必须覆盖排队、运行、成功且无新发现、部分写回后失败、服务重启中断、旧任务信息不足和任务不存在。缺失信息显示“未知／未记录”，不能推断为 0、false 或空集合已确定。

不包含仪表盘、搜索分页、取消任务、自动重试、配置预检、账号体系、实时步骤流、读取原始日志、真实凭据配置或线上部署。管理鉴权、单应用进程配单 SQLite 文件、现有平台与模型边界不变。

## 2. 基线事实与实现约束

以下来自本轮基线代码，开发不能按字段名猜测已有能力：

- `WorkbenchJobController` 只有列表 GET 与手动 POST；成功使用 `Result.success`，JSON `code=200`。POST 的 HTTP 状态是 202，JSON code 仍为 200。
- `WorkbenchJob` 列表包含 `id/repositoryId/number/headSha/status/source/message/createdAt/finishedAt`；数据库另外已有 `base_sha/start_sha`，只是列表 Mapper 未返回。
- `WorkbenchJobs` 按本地仓库 ID、合并请求编号及 head/base/start 整组版本登记和去重；手动请求在同一版本最近任务结束后三秒内复用该任务。成功版本的 webhook 重投复用成功任务。队列满会保留失败任务。
- 启动恢复把 QUEUED/RUNNING 变为 FAILED，不自动重放。等待 diff 的 webhook 可能归并到另一个任务，尚未执行的重复排队行会删除；本轮不新增历史别名机制，原 ID 此时按 404 展示并提供返回列表入口。
- `MergeRequestReviewServiceImpl` 逐条发布、逐条保存发现，最后保存审查记录。`ReviewRecordEntity.publishedCount` 当前为不入库的基本类型 int；失败记录不能可靠表示实际发布数量。
- 任务与审查记录、发现当前没有直接关联。`project_id` 为 GitLab 原生正数 ID、GitHub 原生 ID 的相反数；工作台 `repository_id` 是另一套本地 ID。三者不可混用。
- `failure_detail` 保存异常类型及堆栈；`summary_error` 还可能含异常消息。不能把实体直接返回给浏览器。
- GitHub 讨论标识为 `平台评论 ID * 2 + 类型位`，奇数是概览 issue comment、偶数是行 review comment。GitHub 行评论回退到概览时会追加定位说明，而当前 finding 只保存草稿正文；本轮必须补齐实际发布正文的保存。
- `RepositoryProfiles` 已禁止修改登记后的仓库身份，文档路径和 Skill 则可更新；执行时读取当前仓库配置。详情必须显示本轮保存的文档路径，不能读取当前配置冒充历史依据。

## 3. 持久化与轮次关联

采用直接携带任务 ID 的最小关联，不按 SHA、时间邻近或“最新审查记录”猜测：

| 数据 | 本轮要求 |
| --- | --- |
| `workbench_job` | 增加可空 `details_version` 标记，旧行保持 NULL，新登记任务显式写 1；增加可空 `failure_stage`，保存队列失败、等待 diff 失败、执行异常和中断等任务级终止阶段。保留既有状态集合和已有完整版本列。 |
| `review_record` | 增加可空 `workbench_job_id`、可空 `published_count`、可空 `failure_stage`；关联任务的记录至多一条，使用非 NULL 任务 ID 的唯一索引。旧调用与旧记录允许关联为空。 |
| `finding` | 增加可空 `workbench_job_id` 和查询索引，发布成功后首次保存发现时就携带该 ID；不能等整轮结束才回填。增加可空 `published_body`，新关联发现保存实际发布正文，包括平台适配器添加的回退说明；保留既有 `comment_body` 供历史匹配，避免正文追加说明破坏原有去重。 |

1. 工作台执行入口显式把 job ID 传入本轮审查上下文，再传给审查记录和每一条发现。不能依靠 ThreadLocal 中可变的“最近任务”或查询最新记录来绑定。没有工作台任务的原有入口保持关联 NULL。
2. 一个 job 对应零或一条本轮终态审查记录；已经生成结果时必须恰好对应一条。排队、入队失败、尚未完成或进程中断允许没有审查记录，此时仍可读取已按 job ID 保存的发现。不要为满足非空而伪造 SUCCESS/FAILED 审查记录，也不需要给旧 outcome 约束新增运行态。
3. 所有详情查询先校验 `job.repository_id = URL 中的 repositoryId`，再按该 job ID 查询。返回关联记录前校验其平台存储 ID、合并请求编号与该任务一致；异常关联不对外泄漏其他仓库内容，按安全失败处理，不能退回 SHA 匹配。查询发现同样执行归属校验。
4. 发布失败不能回滚先前已经写回并保存的发现。远端写回和 SQLite 不是一个事务；保留现有补偿清理行为。发布成功但本地保存失败且清理结果不明，或请求超时无法判断远端是否接受时，实际总数必须未知。详情不能声称这种跨系统中断具有严格的恰好一次保证。
5. `published_count` 使用可空 Integer，不设默认 0。正常完成且结果确定时保存本轮实际新增发现数，成功无新发现明确为 0；受控的部分写回失败可保存精确已发布数。发送结果或清理结果不明、结果记录缺失时为 NULL。不能从草稿数量、历史相似发现、列表摘要文字推算。
6. 发现数组是本轮已确认并保存的发布记录；计数为 NULL 时仍展示已保存的 N 条，文案为“已记录 N 条，实际已发布总数未知”。历史失败通知、被去重的旧讨论、只更新解决状态的旧发现均不算本轮新发现。不因后续远端删除讨论而删除本地历史；本轮不查询评论的实时存活状态。
7. spec 是否跳过及实际读入并选作依据的路径来自本轮审查上下文。未到达该阶段、读取失败未形成完整依据时使用 NULL；已完成选择且明确无 spec 为 `specSkipped=true`、`specDocumentPaths=[]`。空配置不等于跳过 spec，自动选中的路径也要保存。当前配置后续变化不改变历史展示。
8. 失败阶段用固定代码保存，原因用可公开的中文说明。不能透传异常消息、请求体、远端响应、模型原文、完整提示词、凭据或内部堆栈。不得直接返回 `failure_detail/summary_error/note_error`；本轮不要求展示编码规范修订的内部错误。
9. 中断仍为 FAILED，持久化 `failure_stage=INTERRUPTED`。`finishedAt` 是恢复时标记结束的时间，不能宣称是进程实际崩溃时刻。已完成的审查记录和发现保留；若结果已经保存、任务收尾前中断，可同时展示该结果和中断提示，不能自动重发。
10. SQLite 只增加列和索引，不删表、不重建覆盖历史、不回填推测关联。新列依赖的索引须在迁移补列之后创建，避免旧表在 schema 初始化时因缺列而启动失败。迁移、索引创建和恢复顺序须明确，重复启动可重复执行。旧 NULL 计数不得被 Java 基本类型转换为 0。

内部类与方法命名由后端决定；以上关联、可空、数据含义和 HTTP 契约不能由单端自行改变。新增记录必须在原有同步执行与去重路径内完成，不新增执行器、全局锁体系或自动重试设施。

## 4. 管理接口契约 v1

### 4.1 路径、鉴权与兼容

新增 `GET /api/repositories/{repositoryId}/jobs/{jobId}`，返回带类型参数的 `Result<ReviewJobDetail>`。两个路径参数都是正整数。沿用 `/api/**` 的 `Authorization: Bearer <工作台管理口令>` 和 `Cache-Control: no-store`。

- `GET /api/repositories/{repositoryId}/jobs` 继续返回现有近 100 条列表，既有字段及含义保持兼容。
- `POST /api/repositories/{repositoryId}/jobs` 请求仍为 `{ "number": 42 }`，响应仍为 `Result<WorkbenchJob>`，不接受旧 SHA、旧配置或用户指定的新 job ID。
- 详情仅查询本地持久化数据和已登记仓库身份，不为打开页面调用平台或模型，也不返回原始 diff 正文。
- HTTP 200 详情成功：`code=200`，`msg=null`，`data` 为详情；失败：`code` 与 HTTP 错误码一致，`msg` 为脱敏说明，`data=null`。错误不使用 `Result.error(String)` 的默认 505。
- 字段按下面完整形状返回；可空字段明确为 JSON null，不用空字符串代替未知。

### 4.2 详情结构

```json
{
  "code": 200,
  "msg": null,
  "data": {
    "job": {
      "id": 101,
      "repositoryId": 7,
      "number": 42,
      "headSha": "1111111111111111111111111111111111111111",
      "status": "FAILED",
      "source": "manual",
      "message": "有 1 条没能写回",
      "createdAt": "2026-11-19T08:00:00Z",
      "finishedAt": "2026-11-19T08:00:10Z"
    },
    "repository": {
      "id": 7,
      "platform": "github",
      "fullName": "example/project",
      "url": "https://github.com/example/project"
    },
    "changeUrl": "https://github.com/example/project/pull/42",
    "diff": {
      "headSha": "1111111111111111111111111111111111111111",
      "baseSha": "2222222222222222222222222222222222222222",
      "startSha": "3333333333333333333333333333333333333333"
    },
    "result": {
      "availability": "RECORDED",
      "reviewRecordId": 88,
      "outcome": "FAILED",
      "occurredAt": "2026-11-19T08:00:09Z",
      "publishedCount": 1,
      "specSkipped": false,
      "specDocumentPaths": ["docs/change-spec.md"],
      "standardDocumentPaths": ["CODING_STANDARD.md"],
      "findings": [
        {
          "id": 210,
          "category": "缺陷",
          "path": "src/example/Service.java",
          "newLine": 24,
          "oldLine": null,
          "citation": null,
          "commentBody": "该分支缺少空值判断，会在输入为空时失败。",
          "discussionUrl": null
        }
      ]
    },
    "failure": {
      "stage": "PUBLISH",
      "reason": "有 1 条没能写回",
      "interrupted": false
    },
    "retry": {
      "eligible": true,
      "activeJobId": null
    }
  }
}
```

示例中的仓库、ID 和 SHA 只是契约示例，不代表一次真实远端审查。各对象本身必有，只有 `failure` 可为 null。字段规则如下：

| 字段 | 类型、可空及含义 |
| --- | --- |
| `job` | 保留现有 WorkbenchJob 的字段形状。id/repositoryId/number 为整数；status 为 QUEUED/RUNNING/SUCCEEDED/FAILED；source 为 manual/webhook；headSha 为已有任务保存的字符串；message、finishedAt 可空。详情中的 message 也须安全映射，不能原样透传未知旧文本。 |
| `job.createdAt/finishedAt` | ISO-8601 UTC 字符串，前者非空；不新增或伪造开始时间。 |
| `repository` | id 为本地 ID；platform 为 github/gitlab；fullName 为经核对的仓库全名；url 为登记的可信 HTTPS 仓库 URL。均非空。 |
| `changeUrl` | string，非空；后端从可信仓库 URL 与正整数编号生成的平台合并请求链接。 |
| `diff.headSha/baseSha/startSha` | 各为 string 或 null，完整值不截断。空值和内部等待标记 `#awaiting-diff` 转为 null，不能把标记作为 SHA 展示。 |
| `result.availability` | 非空枚举，见下节。它说明结果记录可用性，不替代 job.status。 |
| `result.reviewRecordId` | integer 或 null，仅来自直接关联的本轮记录。 |
| `result.outcome` | SUCCESS/FAILED/null，与数据库审查记录一致；注意任务成功状态名称为 SUCCEEDED。 |
| `result.occurredAt` | ISO-8601 UTC string 或 null，审查记录记载时间。 |
| `result.publishedCount` | 非负 integer 或 null；NULL 表示实际总数未知。数值是本轮实际新增发现数，不包括失败通知。 |
| `result.specSkipped` | boolean 或 null；null 为未知，不能按 false 显示。 |
| `result.specDocumentPaths/standardDocumentPaths` | string[] 或 null；null 为未记录或未形成完整依据，[] 为已确定没有该类文档。只返回路径，不返回内容或配置输入。 |
| `result.findings` | 非空数组，按本地 finding id 升序；仅按 job ID 直接关联，空数组本身不证明发布数量为 0。旧任务不得混入同 SHA 的发现。 |
| `findings[].id` | 非空本地 integer，作为列表稳定键；不是平台评论 ID。 |
| `findings[].category` | 编码规范/缺陷/spec/null；新发现须为三类之一，未知展示“未记录类别”。 |
| `findings[].path/newLine/oldLine` | path 为 string/null；两个行号各为正 integer/null。保留左右侧，概览且无可靠位置时显示“概览”。不得伪造行号 0。回退概览可以展示原定位，但不能声称评论仍挂在该行。 |
| `findings[].citation` | string/null，实际保存的引用，按文本展示。 |
| `findings[].commentBody` | 非空 string，实际发布的正文。保留换行，HTML 片段作为文本显示。 |
| `findings[].discussionUrl` | string/null；只有能可靠定位评论且符合可信 URL 约束时提供。无法构造时仍展示发现并允许打开合并请求。 |
| `failure` | 无失败为 null；有本轮失败或任务中断时为下面的固定形状。 |
| `failure.stage` | QUEUE/DIFF/HISTORY/STANDARDS/SPEC/MODEL/VERSION_CHECK/PUBLISH/DISCUSSION_UPDATE/EXECUTION/INTERRUPTED/UNKNOWN；阶段不可确定时 UNKNOWN。 |
| `failure.reason` | 非空脱敏中文 string；不根据堆栈生成原因。旧文本无法安全映射时为“失败原因未记录，请核对远端评论与服务状态”。 |
| `failure.interrupted` | 非空 boolean；从持久化中断阶段得出。已有版本的固定恢复提示可以受控映射为中断，不能广泛解析任意错误字符串。 |
| `retry.eligible` | 非空 boolean，仅 job.status=FAILED 时 true；表示提供失败重试能力，不代表已过冷却或一定创建新任务。 |
| `retry.activeJobId` | integer/null，同一仓库、同一合并请求当前 QUEUED/RUNNING 的任务；若有多个版本排队，确定性返回 ID 最大的一条。不存在则 null。不得仅查近 100 条列表。 |

`result.availability` 判定优先级：

1. `details_version` 为 NULL 的旧任务：LEGACY_UNLINKED；不反查历史匹配，record/count/spec/paths 均 null，findings=[]。已有任务状态、时间和完整版本仍可显示。
2. 新任务存在有效的直接关联审查记录：RECORDED。
3. 新任务未有记录且 QUEUED：NOT_STARTED。
4. 新任务未有记录且 RUNNING：IN_PROGRESS。
5. 新任务已终止但无记录：MISSING。可以同时有已直接关联的部分发现；这些仍须展示，计数为 null。

任务、记录及发现应从一致的本地读取视图返回。新运行中的任务可能已写入部分发现但尚无终态记录；前端显示已记录内容，不宣称本轮完成。审查记录先保存、任务后收尾的短暂窗口允许 RECORDED 与 RUNNING 共存。

### 4.3 失败阶段与安全链接

- QUEUE：入队被拒；DIFF：等待/读取 diff 引用或正文；HISTORY：已有讨论读取；STANDARDS：编码规范选择读取；SPEC：spec 选择读取；MODEL：模型审查；VERSION_CHECK：版本已变或提交核对失败；PUBLISH：发布或保存发现失败；DISCUSSION_UPDATE：旧讨论状态更新；EXECUTION：未能细分的执行失败；INTERRUPTED：进程恢复标记；UNKNOWN：旧数据缺失。
- 部分写回后失败要准确记录失败点，不能因为代码已进入“讨论状态更新”阶段而把发布失败误标成 DISCUSSION_UPDATE。
- PR 链接为 `<repository.url>/pull/<number>`；MR 链接为 `<repository.url>/-/merge_requests/<number>`。保留受信任 GitLab 实例路径及嵌套命名空间。
- GitHub 讨论 key 必须先还原平台 ID：奇数链接 `#issuecomment-<key/2 向下取整>`，偶数链接 `#discussion_r<key/2>`。GitLab 可用已保存的首条 note ID 构造 `#note_<id>`。没有可靠 ID、平台类型或可信地址就返回 null；不能因为原草稿有行号就选择行评论链接，回退后类型可能已改变。
- 不接受浏览器传入的平台 URL，不采用 webhook 或模型提供的 URL 生成导航。后端及前端只允许可信仓库同源 HTTPS 链接，无 userinfo，路径正确；外链使用 `rel="noopener noreferrer"`。无需新增任意 URL 抓取能力。

### 4.4 HTTP 错误矩阵

| HTTP / JSON code | 触发条件与前端行为 |
| --- | --- |
| 200 / 200 | 详情成功；旧信息不足仍为 200，展示缺失状态。 |
| 202 / 200 | 手动/重试受理或返回可复用任务；按 `data.id` 定位，不宣布成功或一定创建新任务。 |
| 400 / 400 | 详情路径参数无效，或手动编号缺失、非正整数等无效请求；固定可操作说明。 |
| 401 / 401 | 未提供或错误管理口令；沿用退出解锁状态，不展示旧详情。 |
| 404 / 404 | 仓库不存在、任务不存在或任务属于其他仓库。详情统一说明“评审任务不存在或不属于所选仓库”，不泄漏真实归属。 |
| 409 / 409 | 手动路径读取到 diff 尚未就绪；保留原详情，提示稍后手动再试。 |
| 502 / 502 | 手动路径无法读取当前合并请求；使用已有脱敏提示。 |
| 503 / 503 | 管理口令未配置，或提交时队列已满；按安全 msg 区分，队列满后允许刷新查看保留的失败任务。 |
| 500 / 500 | 本地读取失败或数据一致性错误；固定说明，不返回 SQL、异常消息或堆栈。 |

鉴权先于参数及存在性检查；未解锁时不能用 404 探测任务。列表和手动接口已有响应结构保持兼容，新增详情的参数绑定错误也须使用 Result，不落入默认 HTML 或不一致的 JSON 错误体。

## 5. 重试交互与并发规则

1. 只有 FAILED（包含中断）的任务提供“重试评审”。QUEUED/RUNNING 显示当前状态及刷新入口，SUCCEEDED 无失败重试入口。保留原有独立手动触发能力。
2. 按钮旁固定说明：“重试会读取当前 PR/MR 的最新版本，并在执行时使用当前仓库配置；不会重放此任务的旧 SHA。”原失败任务、审查记录和发现不删除、不改写为新任务。
3. 中断或发布结果不明时，展示“远端可能已收到部分评论，请先打开 PR/MR 核对已发布评论，再重试”，同时提供可信合并请求链接。不能承诺重试会清除远端已有评论。无需另建确认流程或自动核对远端功能。
4. 前端复用现有 POST 及请求层；提交期间禁用重复操作。若 `retry.activeJobId` 非空，提供“查看正在处理的任务”并定位它，不直接重复 POST。旧详情或列表可能过期，因此后端仍为最终裁定者。
5. 后端的手动入口在同一仓库、同一编号已有活动任务时返回该任务；登记临界区再次核对，覆盖读取平台期间到达的 webhook。沿用现有短暂同步登记和执行路径。webhook 的新完整版本仍沿用现有规则，不能因新增手动保护而丢掉合法的新版本事件。
6. 无活动任务时，手动入口读取当前平台版本，继续按完整 head/base/start 去重。相同完整版本最近任务结束未满三秒时返回该任务；这是 HTTP 202 / code 200，不是新 429 接口。三秒后才允许为该完整版本新建手动轮次。其他版本仍按现有规则处理。
7. POST 返回新 ID：更新列表并打开新详情。返回活动任务 ID：定位已有活动任务。返回终态 ID（可能就是原失败 ID）：定位该任务，明确“本次未创建新任务，请在该任务结束三秒后再试”；不要立即循环重发。前端按 finishedAt 提供冷却提示即可，不依赖客户端时钟作最终判断。
8. 重试、手动触发和 webhook 对同一活动完整版本并发到达时只执行一轮，不重复发布同一发现。三秒后新一轮仍读取已有讨论，通过既有历史去重避免把仍然存在的同一发现重复发出。真实网络不确定或进程中断的剩余限制必须如实提示。
9. 提交失败时保留原任务上下文和明确错误提示；不擅自显示一个客户端生成的任务。旧请求在切仓、切任务、退出会话后完成，不得更新当前页面、跳转到旧仓库或重新启用已失效按钮。

## 6. 前端展示与状态

在现有 `/reviews` 页面内增加任务详情区域，列表提供独立“查看详情”按钮并保留平台链接；不要求新增 history 路由或修改后端 SPA 回退。详情有返回列表/关闭和手动刷新入口。若使用抽屉或弹层，必须处理焦点进入、Escape 关闭和焦点回到触发按钮；页面内区域也必须让键盘能到达详情标题和全部操作。

| 观察到的情况 | 必须显示 |
| --- | --- |
| 首次读取 | 加载提示，不残留上个任务的结果。 |
| QUEUED / NOT_STARTED | 排队中；结果尚未产生，数量未知。等待版完整 SHA 缺项显示未取得。 |
| RUNNING / IN_PROGRESS | 运行中；已有保存发现可以显示，数量未知，不展示虚构步骤进度。 |
| SUCCEEDED / RECORDED / count=0 | “审查完成，本轮没有新增发现”；与未知空态不同。 |
| FAILED / RECORDED / count>0 | 明确“部分评论已发布”，列出本轮发现、精确数量和失败阶段/原因；不能统称完全未执行。 |
| FAILED / MISSING / findings 非空 | 中断或缺失提示，展示已保存发现，发布总数未知。 |
| FAILED / INTERRUPTED | 先核对远端提示；保留恢复时结束时间，不伪造崩溃时间。 |
| LEGACY_UNLINKED | “旧任务未保存轮次关联，详细结果和精确数量未知”；不展示同 SHA 历史发现，也不从旧摘要解析数字。 |
| specSkipped=true / false / null | 分别显示“未选中 spec，本轮跳过 spec 审查”／“已采用 spec”／“是否跳过未记录”。 |
| paths 为 null / [] / 非空 | 分别显示未记录／已确定无该类文档／本轮路径清单，不能用当前配置填空。 |
| 404 | 明确不存在或不属于所选仓库，提供返回列表与刷新入口，清除旧详情。 |
| 网络或其他请求失败 | 显示安全错误及重试读取入口；同一任务已有数据可保留，但标注刷新失败，不装作最新状态。 |

- 请求必须同时绑定 repositoryId、jobId 和会话有效性；用取消或请求代次阻止旧响应覆盖。现有按仓库 key 卸载策略可沿用，但还要覆盖同仓库快速切任务及重试 POST 回包。
- 不强制新增定时轮询；若沿用列表轮询，组件卸载、页面隐藏和换仓规则保持有效。详情刷新也不能出现较旧响应覆盖较新响应。
- 完整 SHA、路径、引用和正文支持换行；320px 宽屏幕无横向页面滚动，桌面信息层次清楚，按钮有清晰名称和可见焦点。加载与结果提示使用适当的 status/alert，不能只靠颜色区分。
- 正文、引用、文件路径都作为文本显示，不执行 HTML，不使用不受控的 `v-html`。本轮不新增 Markdown HTML 渲染器。

## 7. 分工与共享工作树约束

| 角色 | 负责及交付 |
| --- | --- |
| 产品经理 | 本 Issue、此契约、工作树准备；协调契约或范围变化。当前任务不实现前后端、不提交代码。 |
| Java程序员 | `src/` 下关联、增补迁移、详情 DTO/接口/鉴权错误体、实际正文及计数保存、手动入口活动任务保护、后端测试。提交真实文件清单、执行命令与输出摘要、未提交文件和限制。 |
| 前端工程师 | `web/` 下详情与重试、类型及请求层、所有状态、响应竞态、键盘和 320px，提供浏览器场景及验证记录。共享 `scripts/workbench-smoke/` 的修改先与集成负责人协调。 |
| code review工程师 | 唯一集成与发布负责人；独立审查、实跑、记录缺陷、交责任开发修正并复验；协调共享脚本/说明文档，汇总测试，全部已知阻塞关闭后统一 commit、push 和创建指向 main 的 GitHub PR。 |

两端使用本任务实际工作树 `radiant-colden` 与 `cindy/radiant-colden` 分支，不能回到根工作区的旧 `fix/review-recovery-and-dedup` 或旧 `amazing-panini`。工作树路径以任务最终交接的绝对路径为准。开发开始前自行核对分支和基线，并保留本文件的未提交改动。

两端不自行提交、push、建最终 PR，也不同时修改对方目录或共享脚本/契约。契约变化要同步 Issue、本文和两端类型；协调完才能继续依赖该变化的实现。集成负责人拿到两端交接后先核对全部未提交文件，禁止重置、清理或覆盖别人的改动。

## 8. 验收矩阵与发布门槛

下列均为待执行验收；写入本 spec 不表示测试已经通过。

- [ ] **关联隔离**：同仓库同编号同整组 SHA 在三秒后做至少两次手动评审，各有独立任务、记录和发现；同 head 不同 base/start 也不串。两个仓库同编号、GitHub 与 GitLab 相同原生数字 ID/编号分别验证隔离。确认详情未使用 latest/SHA 猜测。
- [ ] **结果真实性**：成功 0、成功有发现、spec 跳过/未到达/自动选中/显式配置、配置后改仍展示原路径；失败通知不算发现，旧讨论不算本轮发布；GitHub 行评论及回退概览保存实际正文、使用正确锚点，GitLab note 链接可还原；空列表与未知计数分开。
- [ ] **部分失败**：第一条写回并入库、后一条平台拒绝；发布后版本变更；本地保存失败及补偿删除失败；网络超时结果不明。逐一检查已确认发现保留、阶段正确、数量已知或未知准确、无敏感字段出现在 DTO。
- [ ] **中断**：至少验证首条发现入库后、审查记录保存前重启；另验证记录已保存但任务收尾前重启。恢复不自动执行；旧失败任务保留，详情与持久化一致，提示先核对远端。
- [ ] **SQLite**：用升级前 schema 和真实旧行启动新应用；不丢失旧 record/finding/job/profile，旧 count/关联保持 NULL；再启动一次无迁移异常。新轮次部分发现、计数、文档路径及结果在重启后保持一致。
- [ ] **重试去重**：连续点击、并发两次 POST、手动与 webhook 同到达、三秒内与边界之后、已有活动任务及最新版本变化。后端验证实际审查和平台发布调用次数；返回已有 ID 就定位已有任务；等待 diff 的 webhook 归并不多跑。
- [ ] **管理接口**：未配置 503、缺失/错误口令 401、正确口令详情 200、跨仓及不存在 404、参数错误 400；手动 diff 未就绪 409、平台不可读 502、队列满 503；所有返回 Result 和安全消息，未知 API 仍 404。
- [ ] **前端竞态**：延迟仓库 A 的详情 GET，切 B 后释放；同仓库先点任务 1 再点 2 后乱序回包；重试 POST 发出后切仓/退出。旧数据、错误、通知和定位均不得污染新上下文。
- [ ] **浏览器**：桌面和 320px，长 SHA/路径、多行正文、包含 HTML 的文本、键盘全流程、404/空态/错误/部分成功/旧数据/中断重试提示。验收实际 jar 提供的页面及同源 API，不只使用接口替身渲染截图。
- [ ] **独立复验闭环**：每条缺陷记录文件:行、触发条件、预期/实际、责任角色与回归标准；责任开发修正后由 code review工程师重新验证，已知缺陷全部关闭才发布。

最终集成工作树必须执行仓库既有检查，并增加上述行为的有意义回归覆盖：

```sh
npm --prefix web ci
npm --prefix web run lint:check
npm --prefix web run format:check
npm --prefix web run build
mvn -B -ntp clean test
mvn -B -ntp test
mvn -B -ntp -Pproduction clean package
python scripts/workbench-smoke/jar_check.py
python scripts/workbench-smoke/run.py --artifacts target/workbench-smoke
```

运行需要 JDK 21、Node 22、Maven、Python 和浏览器脚本所需 Playwright。两个 Python 验收脚本现有覆盖不足以自动证明新增详情/重试；必须协调扩展共享脚本或补充可复现浏览器场景。停止 Vite，使用这次 production clean package 产生的新 jar 执行真实浏览器验收，不复用旧 jar，不提交 target/dist/node_modules/数据库或凭据。

最终报告必须包含集成绝对目录、分支、基线和验证 SHA、Issue、变更与命令/结果、缺陷关闭记录、未提交文件状态、PR URL、该 head 的 CI 状态及尚未覆盖的环境。已验证代码若再改动，重跑受影响检查；发布后核对远端 head 与已验证提交一致。用户授权止于创建向 main 的 PR（用户称 MR），不合并、不自动开启合并。使用本地平台与模型替身须如实说明，不宣称真实 GitHub/GitLab 权限、线上 webhook 或在线模型已验。
