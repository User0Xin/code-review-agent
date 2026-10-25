import { Status, time } from "@/components/status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Empty } from "@/components/ui/empty";
import { useUi } from "@/lib/store";
import { type ModelConnection, type Project, type Session } from "@review/contracts";
import {
  ArrowRight,
  BrainCircuit,
  FolderGit2,
  GitPullRequest,
  Plus,
  Settings2,
} from "lucide-react";

export function ProjectOverview({
  project,
  sessions,
  models,
  onImport,
  onSettings,
}: {
  project: Project;
  sessions: Session[];
  models: ModelConnection[];
  onImport: () => void;
  onSettings: () => void;
}) {
  const ui = useUi();
  return (
    <div className="page project-overview">
      <header className="page-heading compact">
        <div>
          <h1>
            <FolderGit2 size={30} />
            {project.name}
          </h1>
          <p>
            {project.provider} / {project.repository}
          </p>
        </div>
        <div className="heading-actions">
          <Button variant="outline" onClick={onSettings}>
            <Settings2 />
            项目设置
          </Button>
          <Button onClick={onImport}>
            <Plus />
            导入 PR
          </Button>
        </div>
      </header>
      <div className="project-summary">
        <div>
          <span>PR</span>
          <strong>{sessions.length}</strong>
        </div>
        <div>
          <span>待合并 PR</span>
          <strong>{sessions.filter((s) => s.state === "open").length}</strong>
        </div>
        <div>
          <span>审查规范</span>
          <strong className="small">v{project.standardVersion}</strong>
        </div>
        <div>
          <span>审查模型</span>
          <strong className="small">
            {models.find((m) => m.id === project.modelId)?.name || "未配置"}
          </strong>
        </div>
      </div>
      <div className="project-shortcuts">
        <Button variant="outline" onClick={() => ui.setView("experiences")}>
          <BrainCircuit />
          项目经验
          <ArrowRight size={14} />
        </Button>
        <Badge variant="default">{project.autoReview ? "自动审查已开启" : "手动审查"}</Badge>
        <Badge variant="default">{project.autoPublish ? "自动发布已开启" : "手动发布评论"}</Badge>
      </div>
      <section className="overview-section">
        <div className="section-heading">
          <h2>PR</h2>
        </div>
        {sessions.length ? (
          <div className="repository-list">
            {sessions.map((s) => (
              <Button
                variant="ghost"
                className="repository-row"
                key={s.id}
                onClick={() => ui.selectSession(project.id, s.id)}
              >
                <GitPullRequest size={20} />
                <span>
                  <strong>
                    #{s.number} {s.title}
                  </strong>
                  <small className="metadata">
                    <span>{s.branch}</span>
                    <code>{s.headSha.slice(0, 8)}</code>
                    <time>{time(s.updatedAt)}</time>
                  </small>
                </span>
                <Status value={s.state} label={s.state === "open" ? "待合并" : undefined} />
                <ArrowRight size={16} />
              </Button>
            ))}
          </div>
        ) : (
          <Empty className="empty-panel inline-empty">
            <GitPullRequest size={34} />
            <h2>导入第一个 PR</h2>
            <p>选择 PR 或输入编号。</p>
            <Button onClick={onImport}>
              <Plus />
              导入 PR
            </Button>
          </Empty>
        )}
      </section>
    </div>
  );
}
