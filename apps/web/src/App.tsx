import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Empty } from "@/components/ui/empty";
import { ExperiencesView } from "@/features/experiences/experiences-view";
import { JobsView } from "@/features/jobs/jobs-view";
import { ModelDialog } from "@/features/models/model-dialog";
import { ModelsView } from "@/features/models/models-view";
import { ImportDialog } from "@/features/projects/import-dialog";
import { ProjectDialog } from "@/features/projects/project-dialog";
import { ProjectOverview } from "@/features/projects/project-overview";
import { SessionWorkspace } from "@/features/sessions/session-workspace";
import { Welcome } from "@/features/workspace/welcome";
import { api } from "@/lib/api";
import { useUi } from "@/lib/store";
import { cn } from "@/lib/utils";
import { type ModelConnection, type Project, type WorkspaceState } from "@review/contracts";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ChevronRight,
  CircleAlert,
  Cpu,
  FolderGit2,
  GitPullRequest,
  Layers3,
  LoaderCircle,
  Plus,
  PanelLeftClose,
  PanelLeftOpen,
  Radio,
  RefreshCw,
} from "lucide-react";
import { useEffect, useState } from "react";
export default function App() {
  const ui = useUi();
  const query = useQueryClient();
  const workspace = useQuery({
    queryKey: ["workspace"],
    queryFn: () => api<WorkspaceState>("/workspace"),
    refetchInterval: 10_000,
  });
  const [modelOpen, setModelOpen] = useState(false);
  const [editModel, setEditModel] = useState<ModelConnection>();
  const [projectOpen, setProjectOpen] = useState(false);
  const [editProject, setEditProject] = useState<Project>();
  const [importOpen, setImportOpen] = useState(false);
  const [compactNavigation, setCompactNavigation] = useState(
    () => window.matchMedia("(max-width: 700px)").matches,
  );
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  useEffect(() => {
    const media = window.matchMedia("(max-width: 700px)");
    const update = () => {
      setCompactNavigation(media.matches);
      setMobileNavigationOpen(false);
    };
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  useEffect(() => {
    const es = new EventSource("/api/events");
    es.onmessage = () => {
      void query.invalidateQueries();
    };
    return () => es.close();
  }, [query]);
  const data = workspace.data;
  const project = data?.projects.find((p) => p.id === ui.projectId);
  const session = data?.sessions.find((s) => s.id === ui.sessionId && s.projectId === project?.id);
  const openModel = (m?: ModelConnection) => {
    setEditModel(m);
    setModelOpen(true);
  };
  const openProject = (p?: Project) => {
    setEditProject(p);
    setProjectOpen(true);
  };
  const activeJobs =
    data?.jobs.filter((j) => j.status === "running" || j.status === "queued").length || 0;
  const sessionWorkspaceVisible =
    !workspace.isPending && !workspace.error && ui.view === "workspace" && session && project;
  // Overlay navigation never occupies a layout column. Keep its launcher
  // mounted behind the dialog so closing it can restore keyboard focus.
  const navigationCollapsed = compactNavigation || ui.navigationCollapsed;
  const toggleNavigation = () => {
    if (compactNavigation) {
      setMobileNavigationOpen((open) => !open);
      return;
    }
    const collapsed = !ui.navigationCollapsed;
    ui.setNavigationCollapsed(collapsed);
    requestAnimationFrame(() => {
      document
        .querySelector<HTMLButtonElement>(
          `[data-navigation-toggle="${collapsed ? "expand" : "collapse"}"]`,
        )
        ?.focus();
    });
  };
  const navigation = (
    <aside id="workspace-navigation" aria-label="工作区导航栏" className="app-sidebar">
      <div className="sidebar-heading">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            ui.goHome();
            setMobileNavigationOpen(false);
          }}
        >
          <span className="brand-mark" aria-hidden="true">
            <img src="/favicon.svg" width={24} height={24} alt="" />
          </span>
          <span>SparkShield</span>
        </a>
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label="收起左侧栏"
          title="收起左侧栏"
          aria-controls="workspace-navigation"
          aria-expanded
          data-navigation-toggle="collapse"
          onClick={toggleNavigation}
        >
          <PanelLeftClose size={18} />
        </Button>
      </div>

      <nav className="global-nav" aria-label="工作区导航">
        <Button
          variant="ghost"
          className={cn("nav-item", ui.view === "workspace" && !project && "active")}
          onClick={() => {
            ui.goHome();
            setMobileNavigationOpen(false);
          }}
        >
          <Layers3 size={17} />
          <span className="nav-label">工作台</span>
          <span className="nav-count">{data?.projects.length || 0}</span>
        </Button>
        <Button
          variant="ghost"
          className="nav-item"
          disabled={!project}
          onClick={() => {
            setMobileNavigationOpen(false);
            setImportOpen(true);
          }}
        >
          <Plus size={17} />
          <span className="nav-label">导入 PR</span>
        </Button>
      </nav>
      <div className="sidebar-section-title">
        <span>仓库项目</span>
        <Button
          size="icon-xs"
          variant="ghost"
          aria-label="绑定新仓库"
          disabled={!data?.models.some((model) => model.enabled)}
          onClick={() => {
            setMobileNavigationOpen(false);
            openProject();
          }}
        >
          <Plus size={14} />
        </Button>
      </div>
      <div className="project-tree">
        {data?.projects.length ? (
          data.projects.map((p) => (
            <div className="project-tree-item" key={p.id}>
              <div className="project-row">
                <Button
                  variant="ghost"
                  className={cn("project-button", project?.id === p.id && "selected")}
                  title={p.name}
                  onClick={() => {
                    ui.selectProject(p.id);
                    setMobileNavigationOpen(false);
                  }}
                >
                  <FolderGit2 size={16} />
                  <span>{p.name}</span>
                </Button>
                <Button
                  size="icon-xs"
                  variant="ghost"
                  className="project-toggle"
                  aria-label={`${ui.collapsedProjectIds.includes(p.id) ? "展开" : "收起"} ${p.name} 的 PR 列表`}
                  title={`${ui.collapsedProjectIds.includes(p.id) ? "展开" : "收起"} PR 列表`}
                  aria-expanded={!ui.collapsedProjectIds.includes(p.id)}
                  aria-controls={`project-sessions-${p.id}`}
                  onClick={() => ui.toggleProjectCollapsed(p.id)}
                >
                  <ChevronRight
                    size={14}
                    className={ui.collapsedProjectIds.includes(p.id) ? "" : "rotate-90"}
                    aria-hidden="true"
                  />
                </Button>
              </div>
              {!ui.collapsedProjectIds.includes(p.id) && (
                <div className="session-tree" id={`project-sessions-${p.id}`}>
                  {data.sessions
                    .filter((s) => s.projectId === p.id)
                    .map((s) => (
                      <Button
                        variant="ghost"
                        key={s.id}
                        className={cn(
                          "session-button",
                          session?.id === s.id && ui.view === "workspace" && "selected",
                        )}
                        title={`#${s.number} ${s.title}`}
                        onClick={() => {
                          ui.selectSession(p.id, s.id);
                          setMobileNavigationOpen(false);
                        }}
                      >
                        <GitPullRequest size={14} />
                        <span>
                          #{s.number} {s.title}
                        </span>
                        <span className={`session-dot ${s.state}`} />
                      </Button>
                    ))}
                </div>
              )}
            </div>
          ))
        ) : (
          <p className="sidebar-empty">尚未绑定仓库</p>
        )}
      </div>
      <div className="sidebar-bottom">
        <nav className="sidebar-tools" aria-label="配置与任务">
          <Button
            variant="ghost"
            className={cn("nav-item", ui.view === "models" && "active")}
            onClick={() => {
              ui.setView("models");
              setMobileNavigationOpen(false);
            }}
          >
            <Cpu size={17} />
            <span className="nav-label">模型连接</span>
            <span className="nav-count">{data?.models.length || 0}</span>
          </Button>
          <Button
            variant="ghost"
            className={cn("nav-item", ui.view === "jobs" && "active")}
            onClick={() => {
              ui.setView("jobs");
              setMobileNavigationOpen(false);
            }}
          >
            <Radio size={17} />
            <span className="nav-label">后台任务</span>
            {activeJobs > 0 && <span className="nav-count running-count">{activeJobs}</span>}
          </Button>
        </nav>
      </div>
    </aside>
  );
  return (
    <div className="app-shell">
      {compactNavigation ? (
        <Dialog open={mobileNavigationOpen} onOpenChange={setMobileNavigationOpen}>
          <DialogContent className="navigation-dialog" showCloseButton={false}>
            <DialogHeader className="sr-only">
              <DialogTitle>工作区导航</DialogTitle>
              <DialogDescription>选择项目或配置工作区</DialogDescription>
            </DialogHeader>
            {navigation}
          </DialogContent>
        </Dialog>
      ) : (
        !navigationCollapsed && navigation
      )}
      <main className="main-shell">
        {navigationCollapsed && !sessionWorkspaceVisible && (
          <div className="workspace-toolbar">
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="展开左侧栏"
              title="展开左侧栏"
              aria-controls="workspace-navigation"
              aria-expanded={false}
              data-navigation-toggle="expand"
              onClick={toggleNavigation}
            >
              <PanelLeftOpen size={18} />
            </Button>
          </div>
        )}
        {workspace.isPending ? (
          <div className="center-loading">
            <LoaderCircle className="animate-spin" />
            正在读取工作区…
          </div>
        ) : workspace.error ? (
          <Empty className="empty-panel">
            <CircleAlert size={36} />
            <h1>无法连接工作区</h1>
            <p>{workspace.error.message}</p>
            <p className="muted">请确认后端服务已启动，然后重新连接。</p>
            <Button
              onClick={() => {
                void workspace.refetch();
              }}
            >
              <RefreshCw />
              重试
            </Button>
          </Empty>
        ) : (
          data && (
            <>
              {ui.view === "models" ? (
                <ModelsView models={data.models} onEdit={openModel} />
              ) : ui.view === "jobs" ? (
                <JobsView jobs={data.jobs} />
              ) : ui.view === "experiences" && project ? (
                <ExperiencesView project={project} />
              ) : session && project ? (
                <SessionWorkspace
                  key={session.id}
                  id={session.id}
                  project={project}
                  models={data.models}
                  jobs={data.jobs}
                  onSettings={() => openProject(project)}
                  navigationCollapsed={navigationCollapsed}
                  onToggleNavigation={toggleNavigation}
                />
              ) : project ? (
                <ProjectOverview
                  project={project}
                  sessions={data.sessions.filter((s) => s.projectId === project.id)}
                  models={data.models}
                  onImport={() => setImportOpen(true)}
                  onSettings={() => openProject(project)}
                />
              ) : (
                <Welcome data={data} onModel={() => openModel()} onProject={() => openProject()} />
              )}
            </>
          )
        )}
      </main>
      <ModelDialog open={modelOpen} model={editModel} onClose={() => setModelOpen(false)} />
      <ProjectDialog
        open={projectOpen}
        project={editProject}
        models={data?.models || []}
        onClose={() => setProjectOpen(false)}
      />
      {project && (
        <ImportDialog open={importOpen} project={project} onClose={() => setImportOpen(false)} />
      )}
    </div>
  );
}
