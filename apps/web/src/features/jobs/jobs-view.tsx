import { Status, time } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Empty } from "@/components/ui/empty";
import { post } from "@/lib/api";
import { useUi } from "@/lib/store";
import { type Job } from "@review/contracts";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { RefreshCw, Workflow } from "lucide-react";

export function JobsView({ jobs }: { jobs: Job[] }) {
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const retry = useMutation({
    mutationFn: (id: string) => post(`/jobs/${id}/retry`),
    onSuccess: () => {
      void query.invalidateQueries();
      notify("任务已重新加入队列");
    },
    onError: (e) => notify(e.message, true),
  });
  const labels = {
    review: "代码审查",
    reply: "处理评论回复",
    chat: "内部对话",
    summary: "跨 PR 总结",
    publish: "评论发布",
    event: "平台事件",
  };
  return (
    <div className="page">
      <header className="page-heading compact">
        <div>
          <h1>后台任务</h1>
        </div>
        <Button
          variant="outline"
          onClick={() => {
            void query.invalidateQueries();
          }}
        >
          <RefreshCw />
          刷新
        </Button>
      </header>
      {jobs.length ? (
        <div className="jobs-list">
          {[...jobs]
            .sort((a, b) => b.createdAt - a.createdAt)
            .map((j) => (
              <article className="job-row" key={j.id}>
                <div className="job-main">
                  <Workflow size={19} />
                  <div>
                    <strong>{labels[j.kind]}</strong>
                    <p className="metadata">
                      <time>{time(j.createdAt)}</time>
                      <span>
                        尝试 {j.attempts}/{j.maxAttempts}
                      </span>
                      <code>{j.id.slice(0, 8)}</code>
                    </p>
                  </div>
                  <Status value={j.status} />
                  {j.status === "failed" && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={retry.isPending}
                      onClick={() => retry.mutate(j.id)}
                    >
                      <RefreshCw size={13} />
                      重试
                    </Button>
                  )}
                </div>
                {j.error && <p className="job-error">{j.error}</p>}
              </article>
            ))}
        </div>
      ) : (
        <Empty className="empty-panel">
          <Workflow size={36} />
          <h2>还没有后台任务</h2>
          <p>执行记录将在此显示。</p>
        </Empty>
      )}
    </div>
  );
}
