import type {
  Experience,
  Finding,
  NormalizedEvent,
  Project,
  PullRequestSnapshot,
  ReviewRun,
} from "@review/contracts";
import { sql } from "drizzle-orm";
import {
  check,
  foreignKey,
  index,
  integer,
  sqliteTable,
  text,
  unique,
  type AnySQLiteColumn,
} from "drizzle-orm/sqlite-core";

export interface ReviewContextSnapshot {
  project?: Project;
  model: ReviewRun["modelSnapshot"];
  experiences: Experience[];
  priorFindings: Finding[];
  ignorePatterns: string[];
}

export const secrets = sqliteTable("secrets", {
  id: text("id").primaryKey(),
  value: text("value").notNull(),
});

export const modelConnections = sqliteTable(
  "model_connections",
  {
    id: text("id").primaryKey(),
    name: text("name").notNull(),
    baseUrl: text("base_url").notNull(),
    model: text("model").notNull(),
    enabled: integer("enabled", { mode: "boolean" }).notNull(),
    apiKeySecretId: text("api_key_secret_id").references(() => secrets.id, {
      onDelete: "set null",
    }),
    archivedAt: text("archived_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (table) => [check("model_enabled_check", sql`${table.enabled} IN (0, 1)`)],
);

export const chatgptAccounts = sqliteTable("chatgpt_accounts", {
  id: text("id").primaryKey(),
  email: text("email").notNull(),
  subject: text("subject").notNull(),
  clientId: text("client_id").notNull().unique(),
  createdAt: text("created_at").notNull(),
  updatedAt: text("updated_at").notNull(),
});

export const modelChatgptAccounts = sqliteTable("model_chatgpt_accounts", {
  modelId: text("model_connection_id")
    .primaryKey()
    .references(() => modelConnections.id),
  accountId: text("chatgpt_account_id")
    .notNull()
    .references(() => chatgptAccounts.id),
});

export const projects = sqliteTable(
  "projects",
  {
    id: text("id").primaryKey(),
    name: text("name").notNull(),
    provider: text("provider", { enum: ["github", "gitlab"] }).notNull(),
    host: text("host").notNull(),
    repository: text("repository").notNull(),
    repositoryKey: text("repository_key").notNull(),
    modelId: text("model_connection_id")
      .notNull()
      .references(() => modelConnections.id),
    standardVersion: integer("standard_version").notNull(),
    ignorePatterns: text("ignore_patterns_json", { mode: "json" }).$type<string[]>().notNull(),
    autoReview: integer("auto_review", { mode: "boolean" }).notNull(),
    autoPublish: integer("auto_publish", { mode: "boolean" }).notNull(),
    tokenSecretId: text("token_secret_id").references(() => secrets.id, { onDelete: "set null" }),
    webhookSecretId: text("webhook_secret_id").references(() => secrets.id, {
      onDelete: "set null",
    }),
    archivedAt: text("archived_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (table) => [
    unique("projects_repository_unique").on(table.provider, table.host, table.repositoryKey),
    check("projects_provider_check", sql`${table.provider} IN ('github', 'gitlab')`),
    check(
      "projects_strategy_check",
      sql`${table.autoReview} IN (0,1) AND ${table.autoPublish} IN (0,1)`,
    ),
    check("projects_ignore_json_check", sql`json_valid(${table.ignorePatterns})`),
  ],
);

export const standardVersions = sqliteTable(
  "project_standard_versions",
  {
    id: text("id").primaryKey(),
    projectId: text("project_id")
      .notNull()
      .references(() => projects.id),
    version: integer("version").notNull(),
    body: text("body").notNull(),
    sourceExperienceId: text("source_experience_id").references(
      (): AnySQLiteColumn => experiences.id,
    ),
    createdAt: text("created_at").notNull(),
  },
  (table) => [
    unique("standard_version_unique").on(table.projectId, table.version),
    unique("standard_project_unique").on(table.id, table.projectId),
    check("standard_version_positive", sql`${table.version} > 0`),
  ],
);

export const sessions = sqliteTable(
  "sessions",
  {
    id: text("id").primaryKey(),
    projectId: text("project_id")
      .notNull()
      .references(() => projects.id),
    number: integer("pr_number").notNull(),
    title: text("title").notNull(),
    url: text("url").notNull(),
    state: text("state", { enum: ["open", "closed", "merged"] }).notNull(),
    currentSnapshotId: text("current_snapshot_id").references(
      (): AnySQLiteColumn => prSnapshots.id,
    ),
    ciStatus: text("ci_status").notNull(),
    lastReviewAt: text("last_review_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (table) => [
    unique("sessions_pr_unique").on(table.projectId, table.number),
    unique("sessions_project_unique").on(table.id, table.projectId),
    index("sessions_project_idx").on(table.projectId),
    check("sessions_state_check", sql`${table.state} IN ('open','closed','merged')`),
    check("sessions_number_check", sql`${table.number} > 0`),
  ],
);

export const prSnapshots = sqliteTable(
  "pr_snapshots",
  {
    id: text("id").primaryKey(),
    sessionId: text("session_id")
      .notNull()
      .references(() => sessions.id),
    headSha: text("head_sha").notNull(),
    baseSha: text("base_sha").notNull(),
    startSha: text("start_sha"),
    title: text("title").notNull(),
    description: text("description").notNull(),
    url: text("url").notNull(),
    branch: text("branch").notNull(),
    baseBranch: text("base_branch").notNull(),
    state: text("state", { enum: ["open", "closed", "merged"] }).notNull(),
    files: text("files_json", { mode: "json" }).$type<PullRequestSnapshot["files"]>().notNull(),
    coverage: text("coverage_json", { mode: "json" }).$type<{ truncated: boolean }>().notNull(),
    contentHash: text("content_hash").notNull(),
    fetchedAt: text("fetched_at").notNull(),
  },
  (table) => [
    unique("snapshot_content_unique").on(table.sessionId, table.contentHash),
    unique("snapshot_session_unique").on(table.id, table.sessionId),
    check("snapshot_json_check", sql`json_valid(${table.files}) AND json_valid(${table.coverage})`),
    check("snapshot_state_check", sql`${table.state} IN ('open','closed','merged')`),
  ],
);

export const reviewRuns = sqliteTable(
  "review_runs",
  {
    id: text("id").primaryKey(),
    projectId: text("project_id")
      .notNull()
      .references(() => projects.id),
    sessionId: text("session_id")
      .notNull()
      .references(() => sessions.id),
    snapshotId: text("snapshot_id")
      .notNull()
      .references(() => prSnapshots.id),
    standardVersionId: text("standard_version_id")
      .notNull()
      .references(() => standardVersions.id),
    modelId: text("model_connection_id")
      .notNull()
      .references(() => modelConnections.id),
    executionStatus: text("execution_status", {
      enum: ["queued", "running", "completed", "failed", "cancelled"],
    }).notNull(),
    supersededAt: text("superseded_at"),
    trigger: text("trigger").notNull(),
    summary: text("summary").notNull(),
    coverage: text("coverage_json", { mode: "json" }).$type<{ description?: string }>().notNull(),
    inputContext: text("input_context_json", { mode: "json" })
      .$type<ReviewContextSnapshot>()
      .notNull(),
    error: text("error"),
    createdAt: text("created_at").notNull(),
    completedAt: text("completed_at"),
    publishedAt: text("published_at"),
  },
  (table) => [
    unique("run_session_unique").on(table.id, table.sessionId),
    index("runs_session_created_idx").on(table.sessionId, table.createdAt, table.id),
    foreignKey({
      columns: [table.sessionId, table.projectId],
      foreignColumns: [sessions.id, sessions.projectId],
    }),
    foreignKey({
      columns: [table.snapshotId, table.sessionId],
      foreignColumns: [prSnapshots.id, prSnapshots.sessionId],
    }),
    foreignKey({
      columns: [table.standardVersionId, table.projectId],
      foreignColumns: [standardVersions.id, standardVersions.projectId],
    }),
    check(
      "run_execution_check",
      sql`${table.executionStatus} IN ('queued','running','completed','failed','cancelled') AND (${table.executionStatus} != 'cancelled' OR ${table.supersededAt} IS NOT NULL)`,
    ),
    check(
      "run_json_check",
      sql`json_valid(${table.coverage}) AND json_valid(${table.inputContext})`,
    ),
  ],
);

export const findings = sqliteTable(
  "findings",
  {
    id: text("id").primaryKey(),
    sessionId: text("session_id")
      .notNull()
      .references(() => sessions.id),
    fingerprint: text("fingerprint").notNull(),
    currentStatus: text("current_status", { enum: ["open", "resolved", "withdrawn"] }).notNull(),
    firstSeenRunId: text("first_seen_run_id")
      .notNull()
      .references(() => reviewRuns.id),
    lastSeenRunId: text("last_seen_run_id")
      .notNull()
      .references(() => reviewRuns.id),
    remoteId: text("remote_id"),
    remoteThreadId: text("remote_thread_id"),
    publishedAt: text("published_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (table) => [
    unique("finding_session_unique").on(table.id, table.sessionId),
    index("findings_session_status_idx").on(table.sessionId, table.currentStatus),
    foreignKey({
      columns: [table.firstSeenRunId, table.sessionId],
      foreignColumns: [reviewRuns.id, reviewRuns.sessionId],
    }),
    foreignKey({
      columns: [table.lastSeenRunId, table.sessionId],
      foreignColumns: [reviewRuns.id, reviewRuns.sessionId],
    }),
    check("finding_status_check", sql`${table.currentStatus} IN ('open','resolved','withdrawn')`),
  ],
);

export const runFindings = sqliteTable(
  "review_run_findings",
  {
    id: text("id").primaryKey(),
    runId: text("run_id")
      .notNull()
      .references(() => reviewRuns.id),
    findingId: text("finding_id")
      .notNull()
      .references(() => findings.id),
    sessionId: text("session_id")
      .notNull()
      .references(() => sessions.id),
    verdict: text("verdict", { enum: ["open", "resolved", "withdrawn"] }).notNull(),
    title: text("title").notNull(),
    body: text("body").notNull(),
    path: text("path").notNull(),
    line: integer("line"),
    severity: text("severity", { enum: ["high", "medium", "low"] }).notNull(),
    category: text("category", { enum: ["bug", "security", "standard"] }).notNull(),
    evidence: text("evidence").notNull(),
  },
  (table) => [
    unique("run_finding_unique").on(table.runId, table.findingId),
    unique("assessment_session_unique").on(table.id, table.sessionId),
    foreignKey({
      columns: [table.runId, table.sessionId],
      foreignColumns: [reviewRuns.id, reviewRuns.sessionId],
    }),
    foreignKey({
      columns: [table.findingId, table.sessionId],
      foreignColumns: [findings.id, findings.sessionId],
    }),
    check("assessment_verdict_check", sql`${table.verdict} IN ('open','resolved','withdrawn')`),
    check("assessment_line_check", sql`${table.line} IS NULL OR ${table.line} > 0`),
    check("assessment_severity_check", sql`${table.severity} IN ('high','medium','low')`),
    check("assessment_category_check", sql`${table.category} IN ('bug','security','standard')`),
  ],
);

export const messages = sqliteTable(
  "messages",
  {
    id: text("id").primaryKey(),
    projectId: text("project_id")
      .notNull()
      .references(() => projects.id),
    sessionId: text("session_id")
      .notNull()
      .references(() => sessions.id),
    findingId: text("finding_id").references(() => findings.id),
    role: text("role", { enum: ["user", "assistant", "system"] }).notNull(),
    visibility: text("visibility", { enum: ["internal", "external"] }).notNull(),
    body: text("body").notNull(),
    replyToMessageId: text("reply_to_message_id").references((): AnySQLiteColumn => messages.id),
    remoteCommentKind: text("remote_comment_kind"),
    remoteId: text("remote_comment_id"),
    createdAt: text("created_at").notNull(),
  },
  (table) => [
    unique("message_remote_unique").on(table.sessionId, table.remoteCommentKind, table.remoteId),
    unique("message_session_unique").on(table.id, table.sessionId),
    index("messages_session_created_idx").on(table.sessionId, table.createdAt, table.id),
    foreignKey({
      columns: [table.sessionId, table.projectId],
      foreignColumns: [sessions.id, sessions.projectId],
    }),
    foreignKey({
      columns: [table.findingId, table.sessionId],
      foreignColumns: [findings.id, findings.sessionId],
    }),
    check("message_role_check", sql`${table.role} IN ('user','assistant','system')`),
    check("message_visibility_check", sql`${table.visibility} IN ('internal','external')`),
  ],
);

export interface PublicationTarget {
  path?: string;
  line?: number;
  replyToId?: string;
  threadId?: string;
}

export const publications = sqliteTable(
  "publications",
  {
    id: text("id").primaryKey(),
    projectId: text("project_id")
      .notNull()
      .references(() => projects.id),
    sessionId: text("session_id")
      .notNull()
      .references(() => sessions.id),
    kind: text("kind", { enum: ["summary", "finding", "message"] }).notNull(),
    sourceRunId: text("source_run_id").references(() => reviewRuns.id),
    sourceRunFindingId: text("source_run_finding_id").references(() => runFindings.id),
    sourceMessageId: text("source_message_id").references(() => messages.id),
    idempotencyKey: text("idempotency_key").notNull().unique(),
    marker: text("marker").notNull().unique(),
    body: text("body").notNull(),
    target: text("target_json", { mode: "json" }).$type<PublicationTarget>().notNull(),
    snapshotId: text("snapshot_id")
      .notNull()
      .references(() => prSnapshots.id),
    state: text("state", { enum: ["pending", "published"] }).notNull(),
    remoteId: text("remote_comment_id"),
    remoteThreadId: text("remote_thread_id"),
    publishedAt: text("published_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (table) => [
    index("publications_session_idx").on(table.sessionId),
    foreignKey({
      columns: [table.sessionId, table.projectId],
      foreignColumns: [sessions.id, sessions.projectId],
    }),
    foreignKey({
      columns: [table.sourceRunId, table.sessionId],
      foreignColumns: [reviewRuns.id, reviewRuns.sessionId],
    }),
    foreignKey({
      columns: [table.sourceRunFindingId, table.sessionId],
      foreignColumns: [runFindings.id, runFindings.sessionId],
    }),
    foreignKey({
      columns: [table.sourceMessageId, table.sessionId],
      foreignColumns: [messages.id, messages.sessionId],
    }),
    foreignKey({
      columns: [table.snapshotId, table.sessionId],
      foreignColumns: [prSnapshots.id, prSnapshots.sessionId],
    }),
    check(
      "publication_source_check",
      sql`(${table.kind} = 'summary' AND ${table.sourceRunId} IS NOT NULL AND ${table.sourceRunFindingId} IS NULL AND ${table.sourceMessageId} IS NULL) OR (${table.kind} = 'finding' AND ${table.sourceRunId} IS NULL AND ${table.sourceRunFindingId} IS NOT NULL AND ${table.sourceMessageId} IS NULL) OR (${table.kind} = 'message' AND ${table.sourceRunId} IS NULL AND ${table.sourceRunFindingId} IS NULL AND ${table.sourceMessageId} IS NOT NULL)`,
    ),
    check("publication_state_check", sql`${table.state} IN ('pending','published')`),
    check("publication_target_json_check", sql`json_valid(${table.target})`),
  ],
);

export const jobs = sqliteTable(
  "jobs",
  {
    id: text("id").primaryKey(),
    kind: text("kind", {
      enum: ["review", "reply", "chat", "summary", "publish", "event"],
    }).notNull(),
    key: text("key").notNull().unique(),
    projectId: text("project_id").references(() => projects.id),
    sessionId: text("session_id").references(() => sessions.id),
    runId: text("run_id").references(() => reviewRuns.id),
    payload: text("payload", { mode: "json" }).$type<Record<string, unknown>>().notNull(),
    status: text("status", { enum: ["queued", "running", "completed", "failed"] }).notNull(),
    attempts: integer("attempts").notNull().default(0),
    maxAttempts: integer("max_attempts").notNull().default(3),
    availableAt: integer("available_at").notNull(),
    lockedAt: integer("locked_at"),
    error: text("error"),
    createdAt: integer("created_at").notNull(),
  },
  (table) => [
    foreignKey({
      columns: [table.sessionId, table.projectId],
      foreignColumns: [sessions.id, sessions.projectId],
    }),
    foreignKey({
      columns: [table.runId, table.sessionId],
      foreignColumns: [reviewRuns.id, reviewRuns.sessionId],
    }),
    index("jobs_claim_idx").on(table.status, table.availableAt, table.createdAt),
    check(
      "job_kind_check",
      sql`${table.kind} IN ('review','reply','chat','summary','publish','event')`,
    ),
    check("job_status_check", sql`${table.status} IN ('queued','running','completed','failed')`),
    check("job_attempts_check", sql`${table.attempts} >= 0 AND ${table.maxAttempts} > 0`),
    check("job_payload_check", sql`json_valid(${table.payload})`),
  ],
);

export const experiences = sqliteTable(
  "experiences",
  {
    id: text("id").primaryKey(),
    projectId: text("project_id")
      .notNull()
      .references(() => projects.id),
    kind: text("kind", { enum: ["experience", "standard"] }).notNull(),
    body: text("body").notNull(),
    status: text("status", { enum: ["draft", "accepted", "rejected"] }).notNull(),
    generatedByJobId: text("generated_by_job_id").references(() => jobs.id),
    acceptedStandardVersionId: text("accepted_standard_version_id").references(
      (): AnySQLiteColumn => standardVersions.id,
    ),
    decidedAt: text("decided_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (table) => [
    foreignKey({
      columns: [table.acceptedStandardVersionId, table.projectId],
      foreignColumns: [standardVersions.id, standardVersions.projectId],
    }),
    index("experiences_project_status_idx").on(table.projectId, table.status),
    unique("experience_project_unique").on(table.id, table.projectId),
    check("experience_kind_check", sql`${table.kind} IN ('experience','standard')`),
    check("experience_status_check", sql`${table.status} IN ('draft','accepted','rejected')`),
  ],
);

export const experienceSources = sqliteTable(
  "experience_sources",
  {
    id: text("id").primaryKey(),
    experienceId: text("experience_id")
      .notNull()
      .references(() => experiences.id),
    sessionId: text("session_id")
      .notNull()
      .references(() => sessions.id),
    projectId: text("project_id")
      .notNull()
      .references(() => projects.id),
    evidence: text("evidence_json", { mode: "json" })
      .$type<{
        evidence: string;
        provenance: "captured" | "unavailable";
      }>()
      .notNull(),
  },
  (table) => [
    unique("experience_source_unique").on(table.experienceId, table.sessionId),
    foreignKey({
      columns: [table.experienceId, table.projectId],
      foreignColumns: [experiences.id, experiences.projectId],
    }),
    foreignKey({
      columns: [table.sessionId, table.projectId],
      foreignColumns: [sessions.id, sessions.projectId],
    }),
    check("experience_evidence_json_check", sql`json_valid(${table.evidence})`),
  ],
);

export const webhookEvents = sqliteTable(
  "webhook_events",
  {
    id: text("id").primaryKey(),
    projectId: text("project_id").references(() => projects.id),
    provider: text("provider", { enum: ["github", "gitlab"] }),
    deliveryId: text("delivery_id").notNull(),
    event: text("event_json", { mode: "json" }).$type<NormalizedEvent>().notNull(),
    receivedAt: integer("received_at").notNull(),
  },
  (table) => [
    unique("webhook_delivery_unique").on(table.projectId, table.deliveryId),
    check("webhook_event_json_check", sql`json_valid(${table.event})`),
  ],
);
