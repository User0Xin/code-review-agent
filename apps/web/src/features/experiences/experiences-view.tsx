import { MarkdownContent } from "@/components/markdown-content";
import { Status, time } from "@/components/status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Empty } from "@/components/ui/empty";
import { api, patch, post } from "@/lib/api";
import { useUi } from "@/lib/store";
import { type Experience, type Job, type Project } from "@review/contracts";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BrainCircuit, Check, GitPullRequest, LoaderCircle, Sparkles } from "lucide-react";

export function ExperiencesView({ project }: { project: Project }) {
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const q = useQuery({
    queryKey: ["experiences", project.id],
    queryFn: () => api<Experience[]>(`/projects/${project.id}/experiences`),
    refetchInterval: 10_000,
  });
  const generate = useMutation({
    mutationFn: () => post<Job>(`/projects/${project.id}/summarize`),
    onSuccess: () => {
      void query.invalidateQueries();
      notify("跨 PR 总结已加入队列");
    },
    onError: (e) => notify(e.message, true),
  });
  const decide = useMutation({
    mutationFn: ({ id, status }: { id: string; status: "accepted" | "rejected" }) =>
      patch(`/experiences/${id}`, { status }),
    onSuccess: () => {
      void query.invalidateQueries();
      notify("项目经验状态已更新");
    },
    onError: (e) => notify(e.message, true),
  });
  return (
    <div className="page">
      <header className="page-heading compact">
        <div>
          <p className="page-context">{project.name}</p>
          <h1>项目经验</h1>
        </div>
        <Button onClick={() => generate.mutate()} disabled={generate.isPending}>
          {generate.isPending ? <LoaderCircle className="animate-spin" /> : <Sparkles />}
          生成跨 PR 总结
        </Button>
      </header>
      {q.isPending ? (
        <div className="center-loading">
          <LoaderCircle className="animate-spin" />
        </div>
      ) : q.error ? (
        <p className="field-error">{q.error.message}</p>
      ) : q.data.length ? (
        <div className="experience-list">
          {q.data.map((e) => (
            <article className="experience-card" key={e.id}>
              <header>
                <Badge variant="gray">{e.kind === "standard" ? "规范建议" : "项目经验"}</Badge>
                <Status value={e.status} />
                <time>{time(e.createdAt)}</time>
              </header>
              <MarkdownContent>{e.body}</MarkdownContent>
              <div className="experience-sources">
                来源：{e.sourceSessionIds.length} 个 PR 会话
                {e.sourceSessionIds.map((id) => (
                  <Button
                    size="xs"
                    variant="ghost"
                    key={id}
                    onClick={() => useUi.getState().selectSession(project.id, id)}
                  >
                    <GitPullRequest size={11} />
                    {id.slice(0, 8)}
                  </Button>
                ))}
              </div>
              {e.status === "draft" && (
                <footer>
                  <Button
                    size="sm"
                    onClick={() => decide.mutate({ id: e.id, status: "accepted" })}
                    disabled={decide.isPending}
                  >
                    <Check size={13} />
                    {e.kind === "standard" ? "采纳并加入规范" : "采纳经验"}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => decide.mutate({ id: e.id, status: "rejected" })}
                    disabled={decide.isPending}
                  >
                    忽略建议
                  </Button>
                </footer>
              )}
            </article>
          ))}
        </div>
      ) : (
        <Empty className="empty-panel">
          <BrainCircuit size={38} />
          <h2>暂无项目经验</h2>
          <p>完成 PR 审查后，可生成跨 PR 总结。</p>
        </Empty>
      )}
    </div>
  );
}
