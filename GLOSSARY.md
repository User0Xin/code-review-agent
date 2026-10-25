# 领域术语

| 术语                            | 定义                                                                         |
| ------------------------------- | ---------------------------------------------------------------------------- |
| ModelConnection / 模型连接      | 可复用的模型端点与标识。API key 单独加密，不属于前端配置对象。               |
| Project / 项目                  | 一个已绑定仓库，拥有默认模型、规范、忽略路径与审查策略。仓库身份不可修改。   |
| Coding Standard / 编码规范      | 用户给项目的审查标准，显式版本化。                                           |
| Session / PR 会话               | 一个项目中的一个 PR/MR 的持久工作空间，覆盖多次提交和讨论。                  |
| Snapshot / 快照                 | 特定 SHA 的 PR 描述、diff、文件上下文及状态。                                |
| ReviewRun / 审查轮次            | 针对一次快照的模型审查，保留规范、模型和经验版本及有效结果。                 |
| Finding / 发现                  | 具有文件定位与证据的具体问题；跨轮次维护 open/resolved/withdrawn 状态。      |
| Publication / 发布记录          | 本地发送意图、隐藏 marker 与远端评论身份，用于失败恢复和去重。               |
| Message / 消息                  | PR 会话中的用户、Agent 或系统消息；visibility 区分内部讨论与外部评论。       |
| Job / 任务                      | SQLite 中可恢复、可重试的 review/reply/chat/summary/publish/event 执行单元。 |
| Experience / 经验               | 来自一个项目历史 PR 的建议，草稿经采纳后进入未来审查。                       |
| Standard Proposal / 规范建议    | Experience 的一种；采纳后追加到项目规范并增加版本。                          |
| Superseded / 已过时             | 轮次对应的提交已变化或 PR 已关闭，不能继续作为当前结果发布。                 |
| Latest CI Status / 最近 CI 状态 | 最后收到的当前 SHA 的 CI 事件状态，不是所有检查的聚合结论。                  |
