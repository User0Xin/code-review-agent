import { z } from "zod";
import type { Experience } from "./experiences.js";
import type { ModelConnection } from "./models.js";
import type { Project } from "./projects.js";
import type { PullRequestSnapshot } from "./pull-requests.js";

export type RunStatus = "queued" | "running" | "completed" | "failed" | "superseded";

export type RunExecutionStatus = Exclude<RunStatus, "superseded"> | "cancelled";

export interface ReviewRun {
  id: string;
  projectId: string;
  sessionId: string;
  headSha: string;
  status: RunStatus;
  executionStatus?: RunExecutionStatus;
  supersededAt?: string;
  trigger: string;
  standardVersion: number;
  standardSnapshot: string;
  modelSnapshot: Pick<
    ModelConnection,
    "id" | "name" | "baseUrl" | "model" | "authMode" | "chatgptAccountId"
  >;
  experienceIds: string[];
  summary: string;
  error?: string;
  createdAt: string;
  completedAt?: string;
  publishedAt?: string;
  snapshot?: PullRequestSnapshot;
  projectSnapshot?: Project;
  experienceSnapshot?: Experience[];
  ignorePatternsSnapshot?: string[];
  priorFindingIds?: string[];
  priorFindingsSnapshot?: Finding[];
  coverage?: string;
  resultSnapshot?: ReviewResult;
}

export const findingDraftSchema = z.object({
  title: z.string().min(1).max(200),
  body: z.string().min(1).max(6000),
  path: z.string().max(500),
  line: z.number().int().positive().nullable(),
  severity: z.enum(["high", "medium", "low"]),
  category: z.enum(["bug", "security", "standard"]),
  evidence: z.string().min(1).max(3000),
});

export type FindingDraft = z.infer<typeof findingDraftSchema>;

export const reviewResultSchema = z.object({
  summary: z.string().max(12000),
  findings: z.array(findingDraftSchema).max(30),
  resolvedFindingIds: z.array(z.string()).max(100),
});

export type ReviewResult = z.infer<typeof reviewResultSchema>;

export interface Finding extends FindingDraft {
  id: string;
  projectId: string;
  sessionId: string;
  runId: string;
  fingerprint: string;
  status: "open" | "resolved" | "withdrawn";
  remoteId?: string;
  remoteThreadId?: string;
  publishedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Publication {
  id: string;
  projectId: string;
  sessionId: string;
  runId?: string;
  findingId?: string;
  sourceMessageId?: string;
  target?: {
    path?: string;
    line?: number;
    replyToId?: string;
    threadId?: string;
  };
  snapshot?: PullRequestSnapshot;
  marker: string;
  body: string;
  state: "pending" | "published";
  remoteId?: string;
  remoteThreadId?: string;
  createdAt: string;
  updatedAt: string;
}
