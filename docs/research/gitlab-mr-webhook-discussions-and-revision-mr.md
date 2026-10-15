# GitLab 合并请求接口：接收事件、行内讨论、在分支上提交修订

本文只记录 GitLab 官方文档中与这三件事直接相关的接口事实。每条事实后面是拥有该事实的文档 URL。没有版本号的 URL 是 2026-10-15 查阅的当前文档。带版本号的 URL 只用于当前文档已经不再写出的引入版本。

## 结论

1. 合并请求被打开，以及源分支出现新代码，都是 Merge request event。请求头为 `X-Gitlab-Event: Merge Request Hook`，`object_kind` 与 `event_type` 都是 `merge_request`。打开时 `object_attributes.action` 为 `open`。已有合并请求收到新代码时，`action` 为 `update`，并且出现 `object_attributes.oldrev`。项目用顶层 `project`（target project）的 `id`、`path_with_namespace`，加上 `source_project_id` 与 `target_project_id`。合并请求用 `object_attributes.iid` 调 API，用 `object_attributes.id` 对全局 ID。这次变更的两端是 `oldrev` 与 `last_commit.id`。webhook 不带 diff 正文，正文用 versions、diffs 或 compare 再取。
2. 行内讨论走 `POST /projects/:id/merge_requests/:merge_request_iid/discussions`，并提交 `position`。文本行需要 `base_sha`、`head_sha`、`start_sha`、`position_type=text`、`old_path`、`new_path`，再按新增、删除或未改行提供 `new_line` 与 `old_line`。不传 `position` 时，同一接口把线程放在 overview page。Notes API 的 note 不附着到行。提交评论在行号或路径无效时，放在最后一次提交的末尾。已有讨论可以用 discussions 的 PUT 解决、重开或更新正文；同一次请求里 `body` 与 `resolved` 只能设置一个。
3. 修改已有文件并打开合并请求：`POST /projects/:id/repository/commits` 的 `branch` 使用非默认分支名，`start_branch` 或 `start_sha` 指向基线，`actions[].action` 为 `update`。然后 `POST /projects/:id/merge_requests`，`source_branch` 为该分支，`target_branch` 为默认分支。创建合并请求不会合并。写入默认分支只发生在 `branch` 本身就是默认分支，或之后调用 `PUT /projects/:id/merge_requests/:merge_request_iid/merge`。单文件也可以用 `PUT /projects/:id/repository/files/:file_path`，同样用 `branch` 与 `start_branch`。

## 1. 合并请求打开与新提交时 webhook 送来什么

### 事件名称与订阅

项目 webhook 与 group webhook 都会触发 Merge request event。触发条件包括新建合并请求，以及在源分支上新增提交。事件表的表述是：合并请求被创建、编辑、合并或关闭，或源分支新增提交。

https://docs.gitlab.com/user/project/integrations/webhook_events/

项目 hook 要收到该事件，`merge_requests_events` 为 true。该字段的说明是 “Trigger project webhook on merge request events.” 同一页的 Offering 为 GitLab.com、GitLab Self-Managed、GitLab Dedicated。

https://docs.gitlab.com/api/project_webhooks/

请求头是 `X-Gitlab-Event: Merge Request Hook`。顶层 `object_kind` 与 `event_type` 都是 `merge_request`。各 action 共用同一套 JSON。差别在于哪些字段有值，以及 `oldrev`、`system`、`system_action` 这类条件字段是否出现。

https://docs.gitlab.com/user/project/integrations/webhook_events/

`object_attributes.action` 的取值：

- `open`：合并请求被创建。
- `update`：合并请求被更新。文档写明其中包括一般更新、重新请求审查，以及设置或取消 auto-merge。具体类型看 `changes`。
- 其余为 `close`、`reopen`、`approval`、`approved`、`unapproval`、`unapproved`、`merge`。

https://docs.gitlab.com/user/project/integrations/webhook_events/

### 怎样识别“这次是新代码”

`object_attributes.oldrev` 的定义是 “The old commit SHA (only present for push-related events).” 后文写明：它只在 `update` 且存在实际代码变更时出现。文档列出的两类实际代码变更是：

- 新代码被推到源分支。
- 一条 suggestion 被应用。

示例是 `action` 为 `update` 且带 `oldrev`。因此带 `oldrev` 的 `update` 表示这次有代码变更。外部 push 与应用 suggestion 都在这个条件里，文档没有再给字段把二者分开。重新请求审查、提交审查、设置或取消 auto-merge 的示例也是 `action: "update"`，那些示例没有 `oldrev`。

`changes` 只包含这次 action 修改过的属性。文档列出的 `changes` 属性没有 `oldrev`，也没有 `last_commit`。`changes` 为空时事件仍可能触发，文档要求接收方始终查看 `changes` 的内容。判断这次是否为新代码，看 `oldrev` 是否出现。

同一次 push 还可以另发系统触发的合并请求事件。`object_attributes.system` 为 true 时事件由系统触发；`system_action` 仅在 `system` 为 true 时出现。与新提交相关的取值是 `approvals_reset_on_push` 和 `code_owner_approvals_reset_on_push`。这类事件的 `action` 是 `unapproved` 或 `unapproval`。取这次 diff 用的是带 `oldrev` 的那次 `update`。

https://docs.gitlab.com/user/project/integrations/webhook_events/

推送到仓库还会另发 Push event。请求头是 `X-Gitlab-Event: Push Hook`，`object_kind` 为 `push`。载荷有 `project_id`、`project.path_with_namespace`、`ref`、`before`、`after`，以及最多 20 条 `commits`。该示例没有合并请求 `iid`。一次 push 超过 20 个提交时，`commits` 只有最新 20 条，`total_commits_count` 才是实际数量。定位合并请求以 Merge Request Hook 为准。

https://docs.gitlab.com/user/project/integrations/webhook_events/

### 定位项目、合并请求和这次 diff 的字段

顶层 `project` 的定义是 target project。完整 `open` 示例中的项目字段包括 `project.id` 与 `project.path_with_namespace`。`object_attributes.source_project_id` 是源项目 ID，`target_project_id` 是目标项目 ID。`source` 与 `target` 对象里还有各自的 `path_with_namespace`。示例中两个项目 ID 相同；字段本身是分开的，fork 时可以不同。

合并请求：

- `object_attributes.iid` 是 internal ID。diffs、versions、discussions 路径里的 `merge_request_iid` 用它。
- `object_attributes.id` 是 merge request ID。versions 响应里的 `merge_request_id` 对这个 ID。
- `object_attributes.url` 是合并请求页面 URL。
- `object_attributes.source_branch` 与 `target_branch` 是源分支和目标分支。

`object_attributes.last_commit` 的定义是 “The last commit object with details”。完整 `open` 示例里 `last_commit.id` 是该提交的 SHA。文档写 `object_attributes` 是事件之后合并请求的当前状态，且各 action 的 JSON 结构一致，所以 `update` 里的 `last_commit.id` 同样是事件之后的最新提交。

这次代码变更的旧 SHA 是 `oldrev`，新 SHA 是 `last_commit.id`。字段表和完整 `open` 示例没有 diff 正文、patch 或 `diff_refs`。

https://docs.gitlab.com/user/project/integrations/webhook_events/

取 diff 正文有三条文档路径。

当前整份合并请求 diff：

```plaintext
GET /projects/:id/merge_requests/:merge_request_iid/diffs
```

`id` 用项目 ID 或 URL-encoded path，`merge_request_iid` 用 `iid`。每个文件有 `old_path`、`new_path` 和 `diff`。`page` 默认 1，`per_page` 默认 20。`collapsed` 为 true 时文件 diff 被排除，但可以再请求。`too_large` 为 true 时文件 diff 被排除，且不能再取回。该端点受 merge request diff limits 约束，超出时返回有限结果。

https://docs.gitlab.com/api/merge_requests/

按版本定位“这一次”的 head：

```plaintext
GET /projects/:id/merge_requests/:merge_request_iid/versions
```

Discussions 文档写明最新版本排在响应数组的第一项。每一项有 `id`、`head_commit_sha`、`base_commit_sha`、`start_commit_sha`。把 `head_commit_sha` 对上 `last_commit.id`，就得到这次 head 的 diff version。

```plaintext
GET /projects/:id/merge_requests/:merge_request_iid/versions/:version_id
```

该响应包含 `commits` 与 `diffs`。`state` 可以是 `collected`、`overflow`、`without_files`。三个 SHA 的定义是：

- `base_commit_sha`：源分支与目标分支之间的 merge-base。
- `head_commit_sha`：源分支 HEAD。
- `start_commit_sha`：创建该 diff 版本时目标分支的 HEAD。

这是该版本的整份合并请求 diff。`commits` 是这个 diff 版本包含的提交。只比较 `oldrev` 到新 HEAD 的直接差异，用下面的 compare。

https://docs.gitlab.com/api/discussions/

https://docs.gitlab.com/api/merge_requests/

单个提交的 diff：

```plaintext
GET /projects/:id/repository/commits/:sha/diff
```

`:sha` 使用 `last_commit.id`。超过配置的最大文件数后，分页停止，不再返回更多文件。GitLab.com 的具体上限见后文 diff display limits。

https://docs.gitlab.com/api/commits/

`oldrev` 到 `last_commit.id` 的直接比较：

```plaintext
GET /projects/:id/repository/compare
```

`from` 与 `to` 接受 commit SHA 或分支名。`straight` 为 true 时是 `from..to` 的直接比较；默认 false 是使用 merge base 的 `from...to`。`from_project_id` 是 “ID to compare from.” `compare_timeout` 为 true 时，比较超出大小限制或超时：`commits` 数组仍然完整，`diffs` 可能不完整，单个 diff 的 `diff` 字符串也可能为空。`diffs[].too_large` 为 true 时，该文件 diff 被排除且不能再取回。

https://docs.gitlab.com/api/repositories/

### 打开当时 diff 可能尚未就绪

`GET /projects/:id/merge_requests/:merge_request_iid` 的 `diff_refs` 对应当前最新 diff 版本，包含 `base_sha`、`start_sha`、`head_sha`。创建合并请求时 `diff_refs` 与 `changes_count` 一开始为空，之后异步填充。`diff_refs.base_sha` 是源分支与目标分支分叉处的 merge base，`start_sha` 是目标分支提交，`head_sha` 是源分支 head。

https://docs.gitlab.com/api/merge_requests/

`prepared_at` 只在准备步骤完成后写入一次，之后再有变更也不更新。准备步骤包括创建 diff、创建流水线、检查可合并性、链接 Git LFS 对象、发送通知。`detailed_merge_status` 为 `preparing` 时，含义是 “Merge request diff is being created.”

https://docs.gitlab.com/api/merge_requests/

完整 `open` 示例已经带有 `prepared_at` 和 `last_commit`，`changes` 里也可以出现 `prepared_at` 从 null 变为时间戳。该示例没有 `diff_refs`。因此 `open` 事件足以定位项目、`iid` 和 head SHA；diff 正文要等 `diff_refs` 被填上，或 versions 返回后再读。

https://docs.gitlab.com/user/project/integrations/webhook_events/

https://docs.gitlab.com/api/merge_requests/

`GET /projects/:id/merge_requests/:merge_request_iid/changes` 在 GitLab 15.7 弃用，计划在 API v5 移除，文档要求改用 list diffs。弃用页写明目前没有进行中的 v5 开发，这些移除不会发生在 REST API v4 内。

https://docs.gitlab.com/api/merge_requests/

https://docs.gitlab.com/api/rest/deprecations/

## 2. 在 diff 行上创建讨论

### 行位置字段

```plaintext
POST /projects/:id/merge_requests/:merge_request_iid/discussions
```

所有评论都要 `body`、`id`、`merge_request_iid`。`position` 可选，说明是 “Position when creating a diff note.” 一旦提供 `position*`，以下字段必填：

- `position[base_sha]`：表格写的是 “Base commit SHA in the source branch.”
- `position[head_sha]`：该合并请求 HEAD 的 SHA。
- `position[start_sha]`：目标分支上的提交 SHA。
- `position[position_type]`：允许值为 `text`、`image`、`file`。

`position_type` 为 `text` 时，`position[new_path]` 与 `position[old_path]` 必填，并且必须分别是变更后与变更前的文件路径。

行号规则：

- 新增行（diff 中绿色）：只传 `position[new_line]`，不包含 `position[old_line]`。
- 删除行（diff 中红色）：只传 `position[old_line]`，不包含 `position[new_line]`。
- 未变更行：两个行号都传。文件前面的修改若改变了行号，这两个数字可以不同。文档把该行为指向 issue 32516。

可选 `commit_id` 是开始这段讨论所引用的提交 SHA。成功时返回 `201 Created`。

https://docs.gitlab.com/api/discussions/

文档给出的填值顺序是：先调用 versions，取数组第一项，再把 SHA 抄进 position：

- `position[base_sha]` 使用 `base_commit_sha`。
- `position[head_sha]` 使用 `head_commit_sha`。
- `position[start_sha]` 使用 `start_commit_sha`。

versions 页把这三个值定义为 merge-base、源分支 HEAD，以及该 diff 版本创建时的目标分支 HEAD。Discussions 表格对 `base_sha` 的短说明是源分支上的 base commit SHA。示例要求填入的是 versions 响应中的 `base_commit_sha`。`diff_refs` 的定义与 versions 一致，并且在创建后才异步出现。

https://docs.gitlab.com/api/discussions/

https://docs.gitlab.com/api/merge_requests/

多行评论另外使用 `position[line_range]`。`start` 与 `end` 的 `line_code` 和 `type` 必填。`type` 对本次提交新增的行使用 `new`，其他行使用 `old`。`line_code` 的示例是 `adc83b19e793491b1c6ea0fd8b46cd9f32e292fc_5_5`。页内说明的三段依次是文件名的 SHA1、变更前行号、变更后行号。单行文本讨论使用 `new_line` 或 `old_line`，该页没有要求单行再传 `line_code`。

https://docs.gitlab.com/api/discussions/

`position_type` 为 `image` 时使用 `width`、`height`、`x`、`y`。UI 文档写明可以在合并请求和提交详情中对图片上的位置发表评论。

https://docs.gitlab.com/api/discussions/

https://docs.gitlab.com/user/project/merge_requests/changes/

`position_type` 的允许值包含 `file`。`new_path` 与 `old_path` 的必填条件写的是 position type 为 `text` 的时候。当前 Discussions 页没有写出 `file` 的引入版本。GitLab 18.6 的同页仍写着 “`file` introduced in GitLab 16.4.”

https://docs.gitlab.com/api/discussions/

https://docs.gitlab.com/18.6/api/discussions/

Changes 页写明，文件头的 “Comment on this file” 用于对文件留下一般评论，而不把评论钉在某一行。该页 Offering 为 GitLab.com、GitLab Self-Managed、GitLab Dedicated。该页没有把这个按钮写成某个 `position_type`。

https://docs.gitlab.com/user/project/merge_requests/changes/

官方 CLI 把“指定文件、省略行号”写成文件级 diff 评论：`glab mr note create` 的 `--file` 把评论放在最新 diff 版本的该文件上；`--line` 或 `--old-line` 才定位到行；两个行参数都省略时是 file-level comment。

https://docs.gitlab.com/cli/mr/note/create/

`base`、`head`、`start` 或 SHA 不正确时，Discussions 页写明可能会遇到 issue 296829 所描述的 bug。该页没有写出错误响应体，也没有写讨论会被改放到其他位置。

https://docs.gitlab.com/api/discussions/

UI 还有一条行范围限制：评论手柄不能越过 diff 里由 `@@` 标出的 section break；跨 section 时在每一段各写一条评论。调整已打开评论表单的行范围，在 GitLab 19.3 以功能旗标 `rapid_diffs_on_mr_show` 引入，默认启用，并且仍由该旗标控制。

https://docs.gitlab.com/user/discussions/

### 不能挂到行上时，讨论放在哪里

三个接口各自写了落点。

同一条 discussions 创建接口在不传 `position` 时，小节标题是 “Create a new thread on the overview page”。示例只有 `body`。线程出现在合并请求的 overview page，没有行位置。

https://docs.gitlab.com/api/discussions/

Notes API：

```plaintext
POST /projects/:id/merge_requests/:merge_request_iid/notes
```

原文是 “Notes are not attached to specific lines in a merge request.” 它把需要行位置的做法指向 Commits API 的提交评论，以及 Discussions API 的 diff 线程。这条 note 的参数是 `body`，没有 `position`。

https://docs.gitlab.com/api/notes/

对一次提交发表行评论时，行或路径挂不上，评论放在最后一次提交的末尾：

```plaintext
POST /projects/:id/repository/commits/:sha/comments
```

要钉在某文件的某一行，需要完整 commit SHA、`path`、`line`，并把 `line_type` 设为 `new`。表格中 `line_type` 的取值是 `new` 或 `old`。下面任一成立时，评论加在 last commit 的末尾，响应里的 `line`、`line_type`、`path` 为 null：

- `sha` 实际是分支或 tag，并且 `line` 或 `path` 无效。
- `line` 无效，也就是该行不存在。
- `path` 无效，也就是该路径不存在。

Discussions 页的 Line code 一节使用的也是这个提交评论端点。

https://docs.gitlab.com/api/commits/

https://docs.gitlab.com/api/discussions/

Draft notes 在发布前只对作者可见。创建草稿时，`position` 的说明是 “If omitted, creates a regular discussion note.” 发布端点是 `PUT /projects/:id/merge_requests/:merge_request_iid/draft_notes/:draft_note_id/publish`。这是未发布草稿的落点，该页没有把它写成已发布 diff 线程在行号错误时的搬迁规则。

https://docs.gitlab.com/api/draft_notes/

### 已有讨论能否解决或更新

可以。

解决或重开整段线程：

```plaintext
PUT /projects/:id/merge_requests/:merge_request_iid/discussions/:discussion_id
```

`resolved` 必填。说明是 “If true, resolve or reopen the discussion.” 前提是具有 Developer、Maintainer 或 Owner 角色，或者是正在被审查的那次变更的作者。成功返回 `200 OK` 和更新后的 discussion。

https://docs.gitlab.com/api/discussions/

更新或解决线程中的一条 note：

```plaintext
PUT /projects/:id/merge_requests/:merge_request_iid/discussions/:discussion_id/notes/:note_id
```

`body` 与 `resolved` 必须恰好设置一个。`body` 更新内容。`resolved` 解决或重开该 note。`resolved` 以空值发送时，note 原样返回。成功返回 `200 OK`。

https://docs.gitlab.com/api/discussions/

列出讨论时，note 带有 `resolvable`、`resolved`、`resolved_by`、`resolved_at`。`resolvable` 为 true 表示该 note 可以被解决。diff 评论的 `type` 是 `DiffNote`，并带有 `position`。类型说明写明 `DiscussionNote` 不会作为 Note API 的一部分返回。读取和修改 diff 线程使用 discussions 端点。Notes API 的 `PUT /projects/:id/merge_requests/:merge_request_iid/notes/:note_id` 可以修改一条 merge request note 的 `body`；创建说明已经写明这类 note 不附着到行。

https://docs.gitlab.com/api/discussions/

https://docs.gitlab.com/api/notes/

回复使用 `POST /projects/:id/merge_requests/:merge_request_iid/discussions/:discussion_id/notes`，`body` 必填。文档写明这条也可以把一条单独评论变成线程。删除一条线程 note 使用同一路径的 `DELETE`，成功时为 `204 No Content`。

https://docs.gitlab.com/api/discussions/

UI 文档写明：已解决的线程会折叠，用户仍可以继续添加评论；有权限的用户可以重开。合并请求中解决线程的角色是 Developer、Maintainer 或 Owner，或者是 issue 或 merge request 的作者。API 页的作者条件是 “author of the change being reviewed”。两句的作者范围分别以各自页面为准。

https://docs.gitlab.com/user/discussions/

`discussion_locked` 为 true 时，只有项目成员可以添加、编辑或解决评论。设置该标志使用 `PUT /projects/:id/merge_requests/:merge_request_iid` 的 `discussion_locked`。

https://docs.gitlab.com/api/merge_requests/

## 3. 在一个分支上修改已有文件并打开合并请求

提交写入 `branch` 指定的分支。`branch` 使用另一个名字，并用 `start_branch` 或 `start_sha` 从基线创建该分支时，这次提交进入新分支。随后创建合并请求：`source_branch` 为新分支，`target_branch` 为默认分支。创建端点的参数表没有合并动作。

### 用一次提交修改已有文件

```plaintext
POST /projects/:id/repository/commits
```

该页 Offering 为 GitLab.com、GitLab Self-Managed、GitLab Dedicated。

必填字段是 `branch`、`commit_message`、`id`。`branch` 的说明是 “Name of the branch to commit into. To create a new branch, also provide either start_branch or start_sha, and optionally start_project.”

`start_branch` 是新提交的父提交所在分支。未提供 `start_branch` 且也没有 `start_sha` 时，它默认等于 `branch`。它与 `start_sha` 互斥。`start_sha` 必须是完整的 40 位 SHA。`start_project` 默认是当前 `id`。

修改已有文件时，`actions[].action` 为 `update`，并提供 `file_path` 与 `content`。`content` 是文件内容。`encoding` 默认为 `text`，也可以是 `base64`。`last_commit_id` 只在 update、move、delete 中考虑，表示已知的该文件最后提交 ID。

`force` 默认 false。为 true 时，基于 `start_branch` 或 `start_sha` 的新提交覆盖 `branch`，并替换该分支已有历史。若 `force` 为 true 但没有另外指定父提交，`start_branch` 仍默认等于 `branch`，新提交基于当前分支尖端，此时 `force` 没有额外效果。

成功返回 `201 Created`。响应 `id` 是新提交 SHA。

请求大于默认 300 MB 时被拒绝。大于 20 MB 时，每 30 秒最多 3 次。History 写明这些 request size and rate limits 在 GitLab 18.7 引入。`allow_empty` 在 GitLab 18.8 引入，默认 false。

https://docs.gitlab.com/api/commits/

### 只修改一个文件

```plaintext
PUT /projects/:id/repository/files/:file_path
```

`file_path` 是 URL-encoded 全路径。必填 `branch`、`commit_message`、`content`、`file_path`、`id`。`branch` 的说明是 “Name of the branch to create. The commit is added to this branch.” `start_branch` 的说明是 “Name of the base branch to create the branch from.” 成功返回 `200 OK`，响应包含实际写入的 `branch` 与 `file_path`。

失败时返回 `400 Bad Request`，消息不区分原因。文档列出的原因包括：`file_path` 包含 `/../`；新内容与当前内容相同，提交为空；编辑进行中有人用 `git push` 更新了该分支。同一 300 MB 与 20 MB 限制的 History 也写明在 GitLab 18.7 引入。一次修改多个文件仍使用 commits API。这里的 `content` 是文件的新内容。

https://docs.gitlab.com/api/repository_files/

### 打开合并请求

```plaintext
POST /projects/:id/merge_requests
```

必填 `id`、`source_branch`、`target_branch`、`title`。`source_branch` 使用上面的新分支。`target_branch` 使用要对比的分支；项目对象中的默认分支字段名是 `default_branch`。可选 `target_project_id`。参数表中的 `remove_source_branch` 与 `squash` 说明的都是合并发生时的行为。

https://docs.gitlab.com/api/merge_requests/

接受并合并是独立端点：

```plaintext
PUT /projects/:id/merge_requests/:merge_request_iid/merge
```

说明是 “Accepts and merges the changes submitted in a merge request.” 只调用创建端点时，目标分支不会被这次调用改写。GitLab 19.2 引入了一项设置：组或实例可以要求 merge 请求带上 `sha`，且该 SHA 必须等于源分支 HEAD。该要求属于 merge 端点，创建端点的参数表没有这个字段。

https://docs.gitlab.com/api/merge_requests/

默认分支默认受保护。`Allowed to push and merge` 控制谁可以直接推送到已存在的受保护分支，并可以通过合并请求合并；该表把未配置时的默认行为写成 “No one can push.” 同一页后面又写明：`Allowed to push and merge` 未配置时不限制 push；要阻止直接 push，必须把它显式设为 No one。因此“不写入默认分支”依赖请求里的 `branch` 使用另一个名字。若新分支名匹配已有保护规则，直接 push 的权限按该页的保护规则计算。

https://docs.gitlab.com/user/project/repository/branches/protected/

Push rules 中的 “Reject unsigned commits” 拒绝从 GitLab 外部用 `git push` 推上来的未签名提交。通过 GitLab UI 或 API 创建的提交，即使没有签名也被允许。该页 Tier 为 Premium、Ultimate，Offering 为 GitLab.com、GitLab Self-Managed、GitLab Dedicated。

https://docs.gitlab.com/user/project/repository/push_rules/

## gitlab.com 与自建实例

这三件事的路径、JSON 字段和 `action` 取值写在同一套当前文档中。Project webhooks、Commits、Repository files、Draft notes 都标明 Offering 包含 GitLab.com 与 GitLab Self-Managed。会影响这三件事的差异如下。

| 事项 | GitLab.com | 自建实例 | 拥有该事实的文档 |
| --- | --- | --- | --- |
| Webhook 时间戳 | GitLab 19.0 起为带毫秒的 ISO 8601，例如 `2016-08-12T15:23:28.000Z` | 早于 19.0 的实例仍使用 History 中的旧格式，例如 `2016-08-12 15:23:28 UTC` | https://docs.gitlab.com/user/project/integrations/webhook_events/ |
| `position_type=file` | 当前 Discussions 页把它列为允许值 | 18.6 版本文档写明 `file` 在 GitLab 16.4 引入。早于 16.4 的实例没有这个取值 | https://docs.gitlab.com/api/discussions/ 与 https://docs.gitlab.com/18.6/api/discussions/ |
| diff 显示上限 | 不可配置。patch 200 KB，文件 3,000，行 100,000。UI 和返回 diff 的 API 都受此限制 | 可配置。默认 patch 200 KB，文件 1,000，行 50,000。允许的最大值是 500 KB、3,000 文件、100,000 行。达到这三项上限的 10% 时折叠；超过上限时 UI 显示 Too large | https://docs.gitlab.com/user/gitlab_com/ 与 https://docs.gitlab.com/administration/diff_limits/ |
| 每个合并请求的 diff 版本数 | 17.10 在 GitLab.com 启用，上限 1000。达到后该合并请求不能再更新，需要关闭并新建 | 功能旗标 `merge_requests_diffs_limit` 在 17.10 引入且默认关闭，19.0 起正式可用并移除旗标。当前自建管理页的默认值也是 1,000，并且可以修改 | https://docs.gitlab.com/user/gitlab_com/ 与 https://docs.gitlab.com/administration/diff_limits/ |
| 每个合并请求的 diff commits | 上限 1,000,000。达到后不能再更新该合并请求。旗标 `merge_requests_diff_commits_limit` 在 17.11 引入且默认关闭，19.0 起正式可用 | 当前自建管理页的默认值同样是 1,000,000，并且可以修改。达到 maximum diff versions 或 maximum diff commits 后，合并请求不能再更新 | https://docs.gitlab.com/user/gitlab_com/ 与 https://docs.gitlab.com/administration/diff_limits/ |
| 创建提交的请求大小 | 当前文档的 300 MB 拒绝，以及大于 20 MB 时每 30 秒 3 次，History 写明 GitLab 18.7 引入 | 早于 18.7 的实例不包含这条 History 标出的限制 | https://docs.gitlab.com/api/commits/ 与 https://docs.gitlab.com/api/repository_files/ |
| 项目访问令牌 | GitLab.com 上由 Premium 和 Ultimate 支持 | Self-Managed 的 Free、Premium、Ultimate 都支持 | https://docs.gitlab.com/api/rest/authentication/ |

diff 版本上限影响第 1 件：达到上限后，已有合并请求不能再更新，新提交不会再形成新的 diff version。显示上限影响第 1 件和第 2 件：`too_large` 的文件没有可再取回的 diff 正文。`file` 的引入版本影响第 2 件里不钉在某一行的 `position_type`。请求大小限制影响第 3 件。

自建管理页的 Offering 只有 GitLab Self-Managed 和 GitLab Dedicated，并写明 “Diff limits cannot be configured on GitLab.com.” GitLab.com 设置页写明这些显示设置不能在 GitLab.com 上更改，并写明 Self-Managed 可以更改。

https://docs.gitlab.com/administration/diff_limits/

https://docs.gitlab.com/user/gitlab_com/

List diffs 与 raw diffs 都写明端点受 merge request diff limits 约束。提交 diff 端点写明 GitLab.com 的数字见 diff display limits。

https://docs.gitlab.com/api/merge_requests/

https://docs.gitlab.com/api/commits/
