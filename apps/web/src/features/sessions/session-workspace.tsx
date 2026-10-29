import { Badge } from "@/components/ui/badge";
import { Button, ButtonLink } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Empty } from "@/components/ui/empty";
import { MarkdownContent } from "@/components/markdown-content";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@/components/ui/resizable";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { api, post } from "@/lib/api";
import { useUi } from "@/lib/store";
import {
  type Finding,
  type Job,
  type ModelConnection,
  type Project,
  type ReviewRun,
  type SessionDetail,
} from "@review/contracts";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowUpRight,
  Bot,
  CheckCheck,
  ChevronRight,
  CircleAlert,
  FileCode2,
  GitCommitHorizontal,
  LoaderCircle,
  MessageSquare,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Play,
  ShieldCheck,
} from "lucide-react";
import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { usePanelCallbackRef } from "react-resizable-panels";

import { Status, time } from "@/components/status";
import { Composer } from "./composer";
import { RunCard } from "./run-card";
const DiffViewer = lazy(() => import("@/components/diff-viewer"));

export function SessionWorkspace({
  id,
  project,
  models,
  jobs,
  onSettings,
  navigationCollapsed,
  onToggleNavigation,
}: {
  id: string;
  project: Project;
  models: ModelConnection[];
  jobs: Job[];
  onSettings: () => void;
  navigationCollapsed: boolean;
  onToggleNavigation: () => void;
}) {
  const contextCollapsed = useUi((state) => state.contextCollapsed);
  const setContextCollapsed = useUi((state) => state.setContextCollapsed);
  const initialContextSize = useRef(contextCollapsed ? "0px" : "320px");
  const [contextPanel, setContextPanel] = usePanelCallbackRef();
  const contextToggleRef = useRef<HTMLButtonElement>(null);
  const contextCloseRef = useRef<HTMLButtonElement>(null);
  const [compact, setCompact] = useState(() => window.matchMedia("(max-width: 1000px)").matches);
  const [compactContextOpen, setCompactContextOpen] = useState(false);
  useEffect(() => {
    const media = window.matchMedia("(max-width: 1000px)");
    const update = () => {
      setCompact(media.matches);
      setCompactContextOpen(false);
    };
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  useEffect(() => {
    if (contextCollapsed) {
      contextPanel?.collapse();
    } else {
      contextPanel?.expand();
    }
  }, [contextPanel, contextCollapsed]);
  const q = useQuery({
    queryKey: ["session", id],
    queryFn: () => api<SessionDetail>(`/sessions/${id}`),
    refetchInterval: 10_000,
  });
  const query = useQueryClient();
  const notify = useUi((s) => s.notify);
  const [replyFinding, setReplyFinding] = useState<Finding | null>(null);
  const [publishRun, setPublishRun] = useState<ReviewRun | null>(null);
  const review = useMutation({
    mutationFn: () => post<Job>(`/sessions/${id}/review`),
    onSuccess: () => {
      void query.invalidateQueries();
      notify("审查任务已加入队列");
    },
    onError: (e) => notify(e.message, true),
  });
  const publish = useMutation({
    mutationFn: (runId: string) => post<Job>(`/runs/${runId}/publish`),
    onSuccess: () => {
      void query.invalidateQueries();
      notify("评论发布任务已加入队列");
      setPublishRun(null);
    },
    onError: (e) => notify(e.message, true),
  });
  const navigationToggle = navigationCollapsed && (
    <Button
      size="icon-sm"
      variant="ghost"
      aria-label="展开左侧栏"
      title="展开左侧栏"
      aria-controls="workspace-navigation"
      aria-expanded={false}
      data-navigation-toggle="expand"
      onClick={onToggleNavigation}
    >
      <PanelLeftOpen aria-hidden="true" />
    </Button>
  );
  if (q.isPending) {
    return (
      <div className="session-workspace">
        {navigationToggle && <div className="workspace-toolbar">{navigationToggle}</div>}
        <div className="center-loading">
          <LoaderCircle className="animate-spin" /> 正在读取 PR 会话…
        </div>
      </div>
    );
  }
  if (!q.data) {
    return (
      <div className="session-workspace">
        {navigationToggle && <div className="workspace-toolbar">{navigationToggle}</div>}
        <Empty className="empty-panel">
          <CircleAlert />
          <h2>会话暂时无法读取</h2>
          <p>{q.error?.message}</p>
          <Button
            variant="outline"
            onClick={() => {
              void q.refetch();
            }}
          >
            重新加载
          </Button>
        </Empty>
      </div>
    );
  }
  const { session, runs, findings, messages } = q.data;
  const busy = jobs.some(
    (j) =>
      ["queued", "running"].includes(j.status) && j.kind === "review" && j.payload.sessionId === id,
  );
  const events = [
    ...runs.map((r) => ({
      kind: "run" as const,
      id: r.id,
      date: r.createdAt,
      item: r,
    })),
    ...messages.map((m) => ({
      kind: "message" as const,
      id: m.id,
      date: m.createdAt,
      item: m,
    })),
  ].sort((a, b) => a.date.localeCompare(b.date));
  const model = models.find((m) => m.id === project.modelId);
  const contextVisible = compact ? compactContextOpen : !contextCollapsed;
  const toggleContext = () => {
    if (compact) {
      setCompactContextOpen(!compactContextOpen);
    } else {
      const collapsed = !contextCollapsed;
      setContextCollapsed(collapsed);
      requestAnimationFrame(() => {
        (collapsed ? contextToggleRef : contextCloseRef).current?.focus();
      });
    }
  };
  const contextDetails = (
    <ContextDetails
      detail={q.data}
      project={project}
      model={model}
      onSettings={() => {
        setCompactContextOpen(false);
        onSettings();
      }}
    />
  );
  return (
    <div className="session-workspace">
      <ResizablePanelGroup className="review-panels" orientation="horizontal">
        <ResizablePanel id="pr-conversation" minSize={compact ? "0px" : "360px"}>
          <div className="conversation-column">
            <header className="session-heading">
              {navigationToggle}
              <div className="session-heading-main">
                <h1 title={session.title}>{session.title}</h1>
                <div className="session-caption">
                  <span title={project.name}>{project.name}</span>
                  <span aria-hidden="true">/</span>
                  <span>PR #{session.number}</span>
                </div>
              </div>
              <div className="heading-actions">
                <ButtonLink
                  className="session-pr-link"
                  aria-label="查看 PR"
                  href={session.url}
                  target="_blank"
                  rel="noreferrer"
                  title="查看 PR"
                >
                  <ArrowUpRight aria-hidden="true" />
                  <span>查看 PR</span>
                </ButtonLink>
                <Button
                  size="xs"
                  className="session-review-button"
                  aria-label="审查最新提交"
                  disabled={busy || review.isPending || session.state !== "open"}
                  onClick={() => review.mutate()}
                  title="审查最新提交"
                >
                  {busy || review.isPending ? (
                    <LoaderCircle className="animate-spin" aria-hidden="true" />
                  ) : (
                    <Play aria-hidden="true" />
                  )}
                  <span className="review-label-desktop">审查最新提交</span>
                  <span className="review-label-compact" aria-hidden="true">
                    审查
                  </span>
                </Button>
                {(compact || contextCollapsed) && (
                  <Button
                    ref={contextToggleRef}
                    size="icon-sm"
                    variant="ghost"
                    aria-label="展开右侧栏"
                    title="展开右侧栏"
                    aria-controls="pr-context"
                    aria-expanded={contextVisible}
                    onClick={toggleContext}
                  >
                    <PanelRightOpen aria-hidden="true" />
                  </Button>
                )}
              </div>
            </header>

            {q.error && (
              <div role="alert" className="flex shrink-0 items-center gap-3 border-b px-4 py-2">
                <p className="field-error">会话刷新失败：{q.error.message}</p>
                <Button
                  variant="outline"
                  size="xs"
                  disabled={q.isFetching}
                  onClick={() => void q.refetch()}
                >
                  重新加载
                </Button>
              </div>
            )}
            <Tabs defaultValue="conversation" className="conversation-tabs">
              <div className="session-tabbar">
                <TabsList variant="line">
                  <TabsTrigger value="conversation">
                    <MessageSquare size={15} />
                    会话 <span>{events.length}</span>
                  </TabsTrigger>
                  <TabsTrigger value="changes">
                    <FileCode2 size={15} />
                    代码改动 <span>{session.files.length}</span>
                  </TabsTrigger>
                </TabsList>
              </div>
              <TabsContent value="conversation" className="conversation-body">
                <div className="timeline">
                  <div className="timeline-origin">
                    <GitCommitHorizontal size={16} />
                    <span>
                      PR #{session.number}，导入于 {time(session.createdAt)}
                    </span>
                  </div>
                  {!events.length && (
                    <div className="first-review">
                      <div className="outline-symbol">
                        <Bot size={26} />
                      </div>
                      <h2>暂无审查记录</h2>

                      <Button
                        variant="outline"
                        onClick={() => review.mutate()}
                        disabled={busy || review.isPending || session.state !== "open"}
                      >
                        <Play />
                        开始审查
                      </Button>
                    </div>
                  )}
                  {events.map((event) =>
                    event.kind === "run" ? (
                      <RunCard
                        key={event.id}
                        run={event.item}
                        index={runs.findIndex((r) => r.id === event.id) + 1}
                        currentHead={session.headSha}
                        findings={findings}
                        retryError={
                          jobs.find((job) => job.kind === "review" && `run:${job.id}` === event.id)
                            ?.error ?? undefined
                        }
                        onPublish={() => setPublishRun(event.item)}
                        onReply={setReplyFinding}
                      />
                    ) : (
                      <article
                        className={`message message-${event.item.role}`}
                        key={event.id}
                        tabIndex={0}
                      >
                        <div className="message-top">
                          <strong className={event.item.role === "user" ? "sr-only" : undefined}>
                            {event.item.role === "assistant"
                              ? "SparkShield"
                              : event.item.role === "system"
                                ? "系统"
                                : "开发者"}
                          </strong>
                          <span className="message-meta">
                            <span>
                              {event.item.visibility === "external" ? "PR 讨论" : "内部对话"}
                            </span>
                            <span className="message-timestamp">
                              <span aria-hidden="true">·</span>
                              <time dateTime={event.item.createdAt}>
                                {time(event.item.createdAt)}
                              </time>
                            </span>
                          </span>
                        </div>
                        {event.item.findingId && (
                          <p className="reply-reference">
                            回复：
                            {findings.find((f) => f.id === event.item.findingId)?.title ||
                              "审查讨论"}
                          </p>
                        )}
                        <MarkdownContent>{event.item.body}</MarkdownContent>
                      </article>
                    ),
                  )}
                </div>
              </TabsContent>
              <TabsContent value="changes" className="changes-body">
                <Suspense fallback={<div className="center-loading">加载代码差异…</div>}>
                  <DiffViewer files={session.files} />
                </Suspense>
                {session.truncated && (
                  <p className="subtle-note">
                    此 PR 内容已截断，当前审查范围不包含全部代码。请结合平台查看完整改动。
                  </p>
                )}
              </TabsContent>
            </Tabs>
            <Composer
              key={id}
              sessionId={id}
              finding={replyFinding}
              onClear={() => setReplyFinding(null)}
            />
          </div>
        </ResizablePanel>
        {!compact && (
          <>
            <ResizableHandle className="context-handle" aria-label="调整 PR 详情宽度" />
            <ResizablePanel
              id="pr-details-panel"
              panelRef={setContextPanel}
              defaultSize={initialContextSize.current}
              minSize="280px"
              maxSize="42%"
              collapsible
              collapsedSize="0px"
              groupResizeBehavior="preserve-pixel-size"
              className="context-panel"
              onResize={(size, _id, previousSize) => {
                if (previousSize && contextPanel) {
                  const collapsed = size.asPercentage === 0;
                  if (collapsed !== useUi.getState().contextCollapsed) {
                    setContextCollapsed(collapsed);
                  }
                }
              }}
            >
              {!contextCollapsed && (
                <aside id="pr-context" className="context-sidebar" aria-label="PR 详情">
                  <div className="context-heading">
                    <h2>PR 详情</h2>
                    <Button
                      ref={contextCloseRef}
                      size="icon-sm"
                      variant="ghost"
                      aria-label="收起右侧栏"
                      title="收起右侧栏"
                      aria-controls="pr-context"
                      aria-expanded
                      onClick={toggleContext}
                    >
                      <PanelRightClose aria-hidden="true" />
                    </Button>
                  </div>
                  <div className="context-sections">{contextDetails}</div>
                </aside>
              )}
            </ResizablePanel>
          </>
        )}
      </ResizablePanelGroup>
      <Dialog open={compact && compactContextOpen} onOpenChange={setCompactContextOpen}>
        <DialogContent className="form-dialog context-dialog">
          <DialogHeader>
            <DialogTitle>PR 详情</DialogTitle>
            <DialogDescription>
              {project.name} / PR #{session.number}
            </DialogDescription>
          </DialogHeader>
          <div id="pr-context" className="context-sections">
            {contextDetails}
          </div>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!publishRun}
        onOpenChange={(v) => {
          if (!v) {
            setPublishRun(null);
          }
        }}
      >
        <DialogContent className="form-dialog">
          <DialogHeader>
            <DialogTitle>发布审查评论</DialogTitle>
            <DialogDescription>
              将本轮审查结果发布到 {project.repository} 的 PR #{session.number}。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPublishRun(null)}>
              取消
            </Button>
            <Button
              disabled={publish.isPending}
              onClick={() => publishRun && publish.mutate(publishRun.id)}
            >
              {publish.isPending && <LoaderCircle className="animate-spin" />}
              确认发布
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ContextDetails({
  detail,
  project,
  model,
  onSettings,
}: {
  detail: SessionDetail;
  project: Project;
  model?: ModelConnection;
  onSettings: () => void;
}) {
  const { session, runs, findings } = detail;
  const openFindings = findings.filter((finding) => finding.status === "open").length;
  return (
    <>
      <section className="context-overview">
        <div className="context-pr-state">
          <span>PR #{session.number}</span>
          <Status value={session.state} label={session.state === "open" ? "待合并" : undefined} />
        </div>
        <dl className="context-identity">
          <div>
            <dt>分支</dt>
            <dd title={`${session.branch} → ${session.baseBranch}`}>
              <code>{session.branch}</code>
              <span aria-hidden="true">→</span>
              <code>{session.baseBranch}</code>
            </dd>
          </div>
          <div>
            <dt>最新提交</dt>
            <dd>
              <code title={session.headSha}>{session.headSha.slice(0, 8)}</code>
            </dd>
          </div>
        </dl>
      </section>
      <section className="context-pr-description">
        <h3>PR 描述</h3>
        <MarkdownContent>{session.description || "暂无描述"}</MarkdownContent>
      </section>
      <details className="context-disclosure">
        <summary>
          <ShieldCheck size={15} aria-hidden="true" />
          <span>项目规范</span>
          <Badge variant="gray">v{project.standardVersion}</Badge>
          <ChevronRight size={14} className="context-disclosure-caret" aria-hidden="true" />
        </summary>
        <div className="context-disclosure-content">
          <p className="prose-text context-description">{project.standard || "未配置"}</p>
          <Button size="xs" variant="ghost" onClick={onSettings}>
            编辑规范
            <ChevronRight size={14} aria-hidden="true" />
          </Button>
        </div>
      </details>
      <details className="context-disclosure">
        <summary>
          <Bot size={15} aria-hidden="true" />
          <span>审查模型</span>
          <span className="context-summary-value" title={model?.model}>
            {model?.name || "模型不可用"}
          </span>
          <ChevronRight size={14} className="context-disclosure-caret" aria-hidden="true" />
        </summary>
        <div className="context-disclosure-content">
          <p className="context-value">{model?.name || "模型不可用"}</p>
          <p className="muted break-all">{model?.model}</p>
        </div>
      </details>
      <details className="context-disclosure">
        <summary>
          <CheckCheck size={15} aria-hidden="true" />
          <span>运行信息</span>
          <span className="context-summary-value">{openFindings} 个待处理</span>
          <ChevronRight size={14} className="context-disclosure-caret" aria-hidden="true" />
        </summary>
        <div className="context-disclosure-content">
          <dl className="details-list">
            <div>
              <dt>最近 CI</dt>
              <dd>{session.ciStatus || "尚未收到 CI 事件"}</dd>
            </div>
            <div>
              <dt>审查轮次</dt>
              <dd>{runs.length}</dd>
            </div>
            <div>
              <dt>待处理问题</dt>
              <dd>{openFindings}</dd>
            </div>
            <div>
              <dt>已解决</dt>
              <dd>{findings.filter((finding) => finding.status === "resolved").length}</dd>
            </div>
          </dl>
        </div>
      </details>
    </>
  );
}
