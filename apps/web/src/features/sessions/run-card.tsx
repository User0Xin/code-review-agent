import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { MarkdownContent } from "@/components/markdown-content";
import { type Finding, type ReviewRun } from "@review/contracts";
import { Bot, CheckCheck, Clock3, FileCode2, MessageSquare, Send, ShieldCheck } from "lucide-react";

import { Status, time } from "@/components/status";
export function RunCard({
  run,
  index,
  findings,
  currentHead,
  retryError,
  onPublish,
  onReply,
}: {
  run: ReviewRun;
  index: number;
  findings: Finding[];
  currentHead: string;
  retryError?: string;
  onPublish: () => void;
  onReply: (f: Finding) => void;
}) {
  const latestError = run.error?.trim() || retryError?.trim();
  const isRetrying =
    (run.status === "queued" || run.status === "running") && Boolean(retryError?.trim());
  const displayFindings = run.resultSnapshot
    ? run.resultSnapshot.findings.map((draft) => ({
        draft,
        live: findings.find(
          (f) => f.path === draft.path && f.line === draft.line && f.title === draft.title,
        ),
      }))
    : findings.filter((f) => f.runId === run.id).map((live) => ({ draft: live, live }));
  return (
    <article className="review-card">
      <header>
        <span className="agent-symbol">
          <Bot size={18} />
        </span>
        <div>
          <strong>第 {index} 轮审查</strong>
          <p className="metadata">
            <span>{run.modelSnapshot.name}</span>
            <span>规范 v{run.standardVersion}</span>
            <code>{run.headSha.slice(0, 8)}</code>
          </p>
        </div>
        <Status value={run.status} />
      </header>
      <div className="review-card-body">
        {run.status === "failed" ? (
          <div role="alert">
            <p className="field-error">失败原因：{latestError || "未返回具体错误信息。"}</p>
            <p className="muted">
              处理后可点击顶部“审查最新提交”重新审查，或在后台任务中重试本轮。
            </p>
          </div>
        ) : run.summary ? (
          <MarkdownContent>{run.summary}</MarkdownContent>
        ) : (
          <p className="muted">
            {run.status === "queued"
              ? isRetrying
                ? "等待后台重试本轮审查…"
                : "等待后台执行审查…"
              : run.status === "running"
                ? isRetrying
                  ? "正在重试本轮审查…"
                  : "正在获取上下文并分析代码…"
                : "暂无审查总结。"}
          </p>
        )}
        {isRetrying ? (
          <p className="field-error">上次失败原因：{retryError}</p>
        ) : (
          run.status !== "failed" && run.error && <p className="field-error">{run.error}</p>
        )}
        {run.coverage && (
          <p className="coverage">
            <ShieldCheck size={13} />
            {run.coverage}
          </p>
        )}
        {displayFindings.map(({ draft: f, live }, i) => (
          <div className="finding" key={live?.id || `${run.id}-${i}`}>
            <div className="finding-heading">
              <Badge variant={f.severity === "high" ? "red" : "gray"}>
                {{ high: "高风险", medium: "中风险", low: "低风险" }[f.severity]}
              </Badge>
              <strong>{f.title}</strong>
              {live && <Status value={live.status} />}
            </div>
            <div className="finding-location">
              <FileCode2 size={13} />
              <code>
                {f.path}
                {f.line ? `:${f.line}` : ""}
              </code>
              <span>{{ bug: "正确性", security: "安全", standard: "规范" }[f.category]}</span>
            </div>
            <MarkdownContent>{f.body}</MarkdownContent>
            <details>
              <summary>查看证据</summary>
              <MarkdownContent>{f.evidence}</MarkdownContent>
            </details>
            <div className="finding-footer">
              {live && (
                <Button size="sm" variant="ghost" onClick={() => onReply(live)}>
                  <MessageSquare size={13} />
                  讨论
                </Button>
              )}
              {live?.publishedAt && (
                <span>
                  <CheckCheck size={12} />
                  已同步 PR
                </span>
              )}
            </div>
          </div>
        ))}
      </div>
      <footer>
        <time>
          <Clock3 size={12} />
          {time(run.createdAt)}
        </time>
        {run.publishedAt ? (
          <span className="published-label">
            <CheckCheck size={14} />
            已发布
          </span>
        ) : run.status === "completed" && run.headSha === currentHead ? (
          <Button size="sm" variant="outline" onClick={onPublish}>
            <Send size={13} />
            发布评论
          </Button>
        ) : run.headSha !== currentHead ? (
          <span className="muted">历史提交</span>
        ) : null}
      </footer>
    </article>
  );
}
