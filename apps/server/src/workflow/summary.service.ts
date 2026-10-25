import { type JobOfKind } from "@review/contracts";
import { IntegrationError } from "../integrations/integration-error.js";
import { summaryFormatError } from "../integrations/summary-format.js";
import { timestamp } from "./utils.js";
import { type WorkflowContext } from "./workflow-context.js";

export class SummaryService {
  constructor(private readonly context: WorkflowContext) {}
  async summarize(job: JobOfKind<"summary">): Promise<void> {
    const prefix = `experience:${job.id}:`;
    if (this.context.store.experiences.list().some((e) => e.id.startsWith(prefix))) {
      return;
    }
    const project = this.context.project(job.payload.projectId);
    const model = this.context.model(project.modelId);
    const messages = this.context.store.messages
      .listByProject(project.id)
      .filter(
        (m) =>
          m.projectId === project.id &&
          m.visibility === "external" &&
          m.role === "user" &&
          m.remoteId,
      );
    const findings = this.context.store.findings
      .listByProject(project.id)
      .filter(
        (f) =>
          f.projectId === project.id &&
          f.publishedAt &&
          (f.status !== "open" || messages.some((m) => m.findingId === f.id)),
      );
    if (!findings.length) {
      return;
    }
    const sourceSessionIds = [...new Set(findings.map((f) => f.sessionId))];
    const evidence = JSON.stringify(
      sourceSessionIds.map((id) => {
        const session = this.context.session(id);
        return {
          sessionId: id,
          number: session.number,
          url: session.url,
          findings: findings
            .filter((f) => f.sessionId === id)
            .map((f) => ({
              title: f.title,
              body: f.body,
              evidence: f.evidence,
              status: f.status,
              comments: messages.filter((m) => m.findingId === f.id).map((m) => m.body),
            })),
        };
      }),
    );
    const proposals = await this.context.agent.summarize({
      project,
      model,
      apiKey: this.context.store.vault.get(`model:${model.id}`),
      evidence,
      sourceSessionIds,
    });
    for (const proposal of proposals) {
      const error = summaryFormatError(proposal.body);
      if (error) {
        throw new IntegrationError(`跨 PR 总结分节格式无效：${error}`, true);
      }
    }
    this.context.store.transaction(() => {
      for (const [index, proposal] of proposals.slice(0, 30).entries()) {
        if (!["experience", "standard"].includes(proposal.kind) || !proposal.body.trim()) {
          continue;
        }
        this.context.store.experiences.save(
          {
            id: `${prefix}${index}`,
            projectId: project.id,
            kind: proposal.kind,
            body: proposal.body,
            sourceSessionIds,
            status: "draft",
            createdAt: timestamp(),
            updatedAt: timestamp(),
          },
          { evidence, jobId: job.id },
        );
      }
    });
  }
}
