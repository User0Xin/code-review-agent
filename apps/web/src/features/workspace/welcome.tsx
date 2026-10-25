import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useUi } from "@/lib/store";
import { type WorkspaceState } from "@review/contracts";
import { ArrowRight, Check, FolderGit2, Plus } from "lucide-react";

export function Welcome({
  data,
  onModel,
  onProject,
}: {
  data: WorkspaceState;
  onModel: () => void;
  onProject: () => void;
}) {
  const select = useUi((s) => s.selectProject);
  const hasModel = data.models.some((model) => model.enabled);
  const hasProject = data.projects.length > 0;
  return (
    <div className="page welcome-page">
      <header className="page-heading">
        <h1>SparkShield 工作台</h1>
        {hasProject && (
          <Button onClick={onProject} disabled={!hasModel}>
            <Plus />
            绑定仓库
          </Button>
        )}
      </header>
      {!hasProject ? (
        <section className="setup-section" aria-labelledby="setup-title">
          <h2 id="setup-title">开始代码审查</h2>
          <ol className="setup-list">
            <li className="setup-row">
              <span className="step-number">{hasModel ? <Check size={16} /> : "1"}</span>
              <div>
                <h3>添加模型连接</h3>
                <p className="muted">配置 OpenAI 兼容 API，或登录 Codex / ChatGPT 账号</p>
              </div>
              <Button variant="outline" onClick={onModel}>
                {hasModel ? "管理模型" : "添加模型"}
              </Button>
            </li>
            <li className="setup-row">
              <span className="step-number">2</span>
              <div>
                <h3>绑定仓库</h3>
                <p className="muted">GitHub 或 GitLab 仓库</p>
              </div>
              <Button disabled={!hasModel} onClick={onProject}>
                绑定仓库
                <ArrowRight size={14} />
              </Button>
            </li>
            <li className="setup-row">
              <span className="step-number">3</span>
              <div>
                <h3>导入 PR</h3>
                <p className="muted">绑定仓库后，选择 PR 开始审查</p>
              </div>
            </li>
          </ol>
        </section>
      ) : (
        <section aria-labelledby="repositories-title">
          <div className="section-heading">
            <h2 id="repositories-title">仓库</h2>
          </div>
          <div className="repository-list">
            {data.projects.map((p) => (
              <Button
                key={p.id}
                variant="ghost"
                className="repository-row"
                onClick={() => select(p.id)}
              >
                <span className="repo-symbol">
                  <FolderGit2 size={22} />
                </span>
                <span>
                  <strong>{p.name}</strong>
                  <small>
                    {p.provider} / {p.repository}
                  </small>
                </span>
                <Badge variant="gray">
                  {data.sessions.filter((s) => s.projectId === p.id).length} 个 PR
                </Badge>
                <ArrowRight size={17} />
              </Button>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
