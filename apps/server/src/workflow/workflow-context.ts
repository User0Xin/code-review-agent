import {
  type Experience,
  type ModelConnection,
  type Project,
  type PullRequestSnapshot,
  type ReviewRun,
  type Session,
} from "@review/contracts";
import { randomUUID } from "node:crypto";
import { DomainError } from "../common/domain-error.js";
import type { AgentPort, RepositoryPort, ReviewInput } from "../ports.js";
import type { Store } from "../store.js";
import { clone, timestamp } from "./utils.js";

export class WorkflowContext {
  constructor(
    readonly store: Store,
    readonly repository: RepositoryPort,
    readonly agent: AgentPort,
  ) {}
  async importPullRequest(projectId: string, number: number): Promise<Session> {
    const project = this.project(projectId);
    const snapshot = await this.repository.getPullRequest(
      project,
      this.store.vault.get(`repo:${project.id}`),
      number,
    );
    return this.saveSnapshot(project, snapshot);
  }

  project(id: string): Project {
    const project = this.store.projects.get(id);
    if (!project) {
      throw new DomainError("仓库项目不存在");
    }
    return project;
  }
  session(id: string): Session {
    const session = this.store.sessions.get(id);
    if (!session) {
      throw new DomainError("PR 会话不存在");
    }
    return session;
  }
  run(id: string): ReviewRun {
    const run = this.store.runs.get(id);
    if (!run) {
      throw new DomainError("审查轮次不存在");
    }
    return run;
  }
  model(id: string): ModelConnection {
    const model = this.store.models.get(id);
    if (!model?.enabled) {
      throw new DomainError("项目模型不存在或已停用");
    }
    return model;
  }
  experiences(projectId: string): Experience[] {
    return this.store.experiences.listAccepted(projectId);
  }
  saveSnapshot(project: Project, snapshot: PullRequestSnapshot): Session {
    return this.store.transaction(() => {
      const existing = this.store.sessions.findByPullRequest(project.id, snapshot.number);
      const session: Session = {
        ...existing,
        ...clone(snapshot),
        id: existing?.id ?? randomUUID(),
        projectId: project.id,
        ciStatus: existing?.headSha === snapshot.headSha ? existing.ciStatus : "unknown",
        createdAt: existing?.createdAt ?? timestamp(),
        updatedAt: timestamp(),
      };
      this.store.sessions.save(session);
      for (const run of this.store.runs.listBySession(session.id)) {
        if (
          run.sessionId === session.id &&
          (run.headSha !== session.headSha ||
            (run.snapshot &&
              (run.snapshot.baseSha !== session.baseSha ||
                run.snapshot.startSha !== session.startSha)) ||
            session.state !== "open") &&
          run.status !== "superseded"
        ) {
          this.store.runs.save({
            ...run,
            status: "superseded",
            completedAt: run.completedAt ?? timestamp(),
          });
        }
      }
      return session;
    });
  }
  async liveInput(sessionId: string): Promise<ReviewInput> {
    const session = this.session(sessionId);
    const project = this.project(session.projectId);
    const snapshot = await this.importPullRequest(project.id, session.number);
    const model = this.model(project.modelId);
    return {
      project,
      snapshot,
      model,
      apiKey: this.store.vault.get(`model:${model.id}`),
      experiences: this.experiences(project.id),
      priorFindings: this.store.findings.listBySession(sessionId),
    };
  }
  inputForRun(run: ReviewRun): ReviewInput {
    if (!run.snapshot) {
      throw new DomainError("审查快照缺失");
    }
    const configured = this.project(run.projectId);
    const project = {
      ...(run.projectSnapshot ?? configured),
      standard: run.standardSnapshot,
      standardVersion: run.standardVersion,
      ignorePatterns: run.ignorePatternsSnapshot ?? configured.ignorePatterns,
    };
    const connection = this.model(run.modelSnapshot.id);
    if (
      (connection.authMode ?? "api-key") !== (run.modelSnapshot.authMode ?? "api-key") ||
      connection.chatgptAccountId !== run.modelSnapshot.chatgptAccountId
    ) {
      throw new DomainError("模型登录方式或账号已变更，请发起新审查");
    }
    if (connection.baseUrl !== run.modelSnapshot.baseUrl) {
      throw new DomainError("模型连接地址已变更，请发起新审查；旧轮次不会把新凭据发送到旧地址");
    }
    const model = { ...connection, ...run.modelSnapshot };
    return {
      project,
      snapshot: clone(run.snapshot),
      model,
      apiKey: this.store.vault.get(`model:${model.id}`),
      experiences: clone(run.experienceSnapshot ?? []),
      priorFindings: clone(run.priorFindingsSnapshot ?? []),
    };
  }

  async isCurrent(run: ReviewRun): Promise<boolean> {
    const project = run.projectSnapshot ?? this.project(run.projectId);
    const session = this.session(run.sessionId);
    const token = this.store.vault.get(`repo:${project.id}`);
    const revision = this.repository.getRevision
      ? await this.repository.getRevision(project, token, session.number)
      : await this.importPullRequest(project.id, session.number);
    const current =
      revision.state === "open" &&
      revision.headSha === run.headSha &&
      (!run.snapshot ||
        (revision.baseSha === run.snapshot.baseSha && revision.startSha === run.snapshot.startSha));
    if (this.repository.getRevision && !current) {
      this.store.runs.save({
        ...this.run(run.id),
        status: "superseded",
        completedAt: run.completedAt ?? timestamp(),
      });
      // Fetch complete new material only when the revision changed; never relabel old code with a new SHA.
      this.saveSnapshot(
        project,
        await this.repository.getPullRequest(project, token, session.number),
      );
    }
    return current && this.run(run.id).status !== "superseded";
  }
}
