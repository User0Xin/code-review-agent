# Issue tracker：GitHub

本仓库的 issue 与 spec 以 GitHub Issues 为准。所有操作使用 `gh` CLI。

## 约定

- **创建 issue**：`gh issue create --title "..." --body "..."`。多行正文用 heredoc。
- **读取 issue**：`gh issue view <number> --json number,title,body,labels,comments`。
- **列出 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，按需要加上 `--label` 与 `--state`。
- **把 issue 设为父 issue 的子 issue**：`gh issue create --parent <parent> ...`，或事后 `gh issue edit <parent> --add-sub-issue <child>`（`gh` 2.94+）。更旧的 `gh`：`gh api --method POST repos/<owner>/<repo>/issues/<parent>/sub_issues -F sub_issue_id=<child-db-id>`（数据库 id，见下方 **Blocking**）。若未启用 sub-issues，在子 issue 正文顶部写 `Part of #<parent>`。
- **评论**：`gh issue comment <number> --body "..."`
- **添加 / 移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭**：`gh issue close <number> --comment "..."`

从 `git remote -v` 推断仓库；在 clone 内运行时 `gh` 会自动完成。

## Pull requests as a triage surface

**PRs as a request surface: no.** _（若本仓库把外部 PR 当作功能请求，把这里改成 `yes`；`/triage` 读取这个标记。）_

设为 `yes` 时，PR 与 issue 走同一套标签和状态，使用对应的 `gh pr` 命令：

- **读取 PR**：`gh pr view <number> --comments`，diff 用 `gh pr diff <number>`。
- **列出待分拣的外部 PR**：`gh api --paginate 'repos/{owner}/{repo}/pulls?state=open' --jq '.[] | select(.author_association | IN("OWNER","MEMBER","COLLABORATOR") | not) | {number, title, author: .user.login, author_association, labels: [.labels[].name]}'`。
- **评论 / 打标签 / 关闭**：`gh pr comment`、`gh pr edit --add-label` / `--remove-label`、`gh pr close`。

GitHub 的 issue 与 PR 共用编号空间，单独的 `#42` 可能是其中任一：先 `gh pr view 42`，失败再 `gh issue view 42`。

## 当技能说 "publish to the issue tracker"

创建一个 GitHub issue。

## 当技能说 "fetch the relevant ticket"

按上面 **读取 issue** 的方式读取。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是一条 issue，**child** issue 是具体 ticket。

- **Map**：一条带 `wayfinder:map` 标签的 issue，正文包含 Notes / Decisions-so-far / Fog。`gh issue create --label wayfinder:map`。
- **Child ticket**：作为 GitHub sub-issue 挂到 map 上（见 **把 issue 设为父 issue 的子 issue**）。若未启用 sub-issues，把 child 加进 map 正文的任务列表，并在 child 正文顶部写 `Part of #<map>`。标签：`wayfinder:<type>`（`research` / `prototype` / `grilling` / `task`）。被认领后，ticket 指派给正在推进的开发者。
- **Blocking**：使用 GitHub 原生 issue dependencies，作为规范且在 UI 中可见的依赖关系。添加依赖：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是 blocker 的数字 **database id**（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，不是 `#number`，也不是 `node_id`）。GitHub 在 `issue_dependencies_summary.blocked_by` 中报告仍开放的 blocker。若 dependencies 不可用，退回到 child 正文顶部的 `Blocked by: #<n>, #<n>`。所有 blocker 都关闭后，ticket 才解除阻塞。
- **Frontier query**：列出 map 下仍开放的 children（`gh issue list --state open`，范围限定为 map 的 sub-issues 或任务列表），去掉仍有开放 blocker 的（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行里仍有开放 issue）以及已有 assignee 的；按 map 中的顺序取第一条。
- **Claim**：`gh issue edit <n> --add-assignee @me`，这是该会话的第一次写入。
- **Resolve**：`gh issue comment <n> --body "<answer>"`，然后 `gh issue close <n>`，再把一条上下文指针（gist + 链接）追加到 map 的 Decisions-so-far。
