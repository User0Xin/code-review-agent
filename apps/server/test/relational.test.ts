import type { Finding, FindingDraft, ReviewRun } from "@review/contracts";
import Database from "better-sqlite3";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { fingerprint } from "../src/common/finding-fingerprint.js";
import { ExperienceService } from "../src/configuration/experience.service.js";
import { Store } from "../src/store.js";
import {
  fixtureModel,
  fixtureProject,
  fixtureSnapshot,
  fixtureTime,
  seedWorkspace,
} from "./fixtures/stored-workspace.js";

const cleanups: Array<() => void> = [];
afterEach(() => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    cleanup();
  }
});

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), "review-relational-"));
  cleanups.push(() => rmSync(directory, { recursive: true, force: true }));
  const filename = join(directory, "review.db");
  const store = new Store(filename);
  cleanups.push(() => store.close());
  seedWorkspace(store);
  const sql = new Database(filename);
  sql.pragma("foreign_keys = ON");
  cleanups.push(() => sql.close());
  return { store, sql, filename };
}

const originalDraft: FindingDraft = {
  title: "Unchecked input",
  body: "Original explanation",
  path: "src/input.ts",
  line: 2,
  severity: "high",
  category: "security",
  evidence: "const unsafe = input;",
};

function createRun(store: Store, id: string, sessionId = "s1"): ReviewRun {
  const session = store.sessions.get(sessionId);
  if (!session) {
    throw new Error("fixture session missing");
  }
  const run: ReviewRun = {
    id,
    projectId: "p",
    sessionId,
    headSha: session.headSha,
    status: "running",
    trigger: "manual",
    standardVersion: 1,
    standardSnapshot: fixtureProject.standard,
    modelSnapshot: fixtureModel,
    experienceIds: [],
    summary: "",
    snapshot: session,
    projectSnapshot: fixtureProject,
    priorFindingsSnapshot: store.findings.listBySession(sessionId),
    createdAt: new Date().toISOString(),
  };
  return store.runs.save(run);
}

function createFinding(store: Store, runId: string, draft = originalDraft): Finding {
  const finding: Finding = {
    ...draft,
    id: "f1",
    projectId: "p",
    sessionId: "s1",
    runId,
    fingerprint: fingerprint(draft),
    status: "open",
    createdAt: fixtureTime,
    updatedAt: fixtureTime,
  };
  return store.findings.save(finding);
}

describe("relational persistence", () => {
  it("uses domain tables, enforces ownership, and normalizes GitHub repository identity", () => {
    const { store, sql } = fixture();
    const tables = sql
      .prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
      .all();
    expect(tables).toHaveLength(17);
    expect(sql.pragma("foreign_key_check")).toEqual([]);
    expect(() =>
      store.projects.save({ ...fixtureProject, id: "duplicate", repository: "Team/Repo" }),
    ).toThrow();
    expect(() =>
      store.projects.save({ ...fixtureProject, id: "invalid-model", modelId: "missing" }),
    ).toThrow();
    const run = createRun(store, "r1");
    expect(() =>
      store.findings.save({
        ...originalDraft,
        id: "wrong-owner",
        projectId: "p",
        sessionId: "s2",
        runId: run.id,
        fingerprint: "wrong",
        status: "open",
        createdAt: fixtureTime,
        updatedAt: fixtureTime,
      }),
    ).toThrow();
  });

  it("preserves every round's assessment and separates completed execution from supersession", () => {
    const { store, sql } = fixture();
    const firstRun = createRun(store, "r1");
    const finding = createFinding(store, firstRun.id);
    store.runs.save({ ...firstRun, status: "completed", summary: "First review" });
    const secondRun = createRun(store, "r2");
    store.findings.save({
      ...finding,
      runId: secondRun.id,
      body: "A newer explanation",
      evidence: "const changed = input;",
    });
    store.runs.save({ ...secondRun, status: "completed", summary: "Second review" });
    expect(store.runs.get("r1")?.resultSnapshot?.findings[0]?.body).toBe("Original explanation");
    expect(store.runs.get("r2")?.resultSnapshot?.findings[0]?.body).toBe("A newer explanation");
    const thirdRun = createRun(store, "r3");
    store.runs.save({
      ...thirdRun,
      status: "completed",
      resultSnapshot: { summary: "Fixed", findings: [], resolvedFindingIds: [finding.id] },
    });
    expect(store.findings.get(finding.id)?.status).toBe("resolved");
    expect(store.findings.listAssessments("r3")[0]?.verdict).toBe("resolved");
    const fourthRun = createRun(store, "r4");
    store.findings.save({ ...finding, runId: "r4", body: "Reopened with a new explanation" });
    store.runs.save({ ...fourthRun, status: "completed" });
    const thirdCompleted = store.runs.get("r3");
    if (!thirdCompleted) {
      throw new Error("fixture run missing");
    }
    store.runs.save({ ...thirdCompleted, status: "superseded" });
    expect(store.findings.get(finding.id)).toMatchObject({
      status: "open",
      body: "Reopened with a new explanation",
    });
    const completed = store.runs.get("r1");
    if (!completed) {
      throw new Error("fixture run missing");
    }
    store.runs.save({ ...completed, status: "superseded" });
    expect(store.runs.get("r1")).toMatchObject({
      status: "superseded",
      executionStatus: "completed",
    });
    expect(store.runs.get("r1")?.resultSnapshot?.findings[0]?.evidence).toBe(
      originalDraft.evidence,
    );
    expect(() =>
      sql.prepare("UPDATE review_run_findings SET body = 'rewritten' WHERE run_id = 'r1'").run(),
    ).toThrow("immutable");
    expect(() =>
      sql.prepare("UPDATE review_runs SET input_context_json = '{}' WHERE id = 'r1'").run(),
    ).toThrow("immutable");
  });

  it("deduplicates complete snapshot materials and keeps descriptions and coverage in snapshot identity", () => {
    const { store, sql } = fixture();
    const firstId = store.sessions.saveSnapshot("s1", fixtureSnapshot);
    expect(store.sessions.saveSnapshot("s1", fixtureSnapshot)).toBe(firstId);
    const changedDescription = store.sessions.saveSnapshot("s1", {
      ...fixtureSnapshot,
      description: "More context",
    });
    const changedCoverage = store.sessions.saveSnapshot("s1", {
      ...fixtureSnapshot,
      truncated: true,
    });
    expect(new Set([firstId, changedDescription, changedCoverage]).size).toBe(3);
    expect(() =>
      sql.prepare("UPDATE pr_snapshots SET description = 'rewritten' WHERE id = ?").run(firstId),
    ).toThrow("immutable");
    expect(() =>
      sql.prepare("UPDATE sessions SET current_snapshot_id = ? WHERE id = 's2'").run(firstId),
    ).toThrow("another session");
  });

  it("freezes publication body, source, snapshot and target while reconciling delivery status", () => {
    const { store, sql } = fixture();
    const run = createRun(store, "r1");
    const finding = createFinding(store, run.id);
    const publication = store.publications.save({
      id: "pub1",
      projectId: "p",
      sessionId: "s1",
      runId: run.id,
      findingId: finding.id,
      marker: "<!-- review-agent:pub1 -->",
      body: "Original comment",
      state: "pending",
      target: { path: "src/input.ts", line: 2 },
      snapshot: fixtureSnapshot,
      createdAt: fixtureTime,
      updatedAt: fixtureTime,
    });
    store.publications.save({
      ...publication,
      body: "Replacement body",
      target: { path: "other.ts", line: 99 },
      state: "published",
      remoteId: "remote1",
    });
    expect(store.publications.get("pub1")).toMatchObject({
      body: "Original comment",
      target: { path: "src/input.ts", line: 2 },
      state: "published",
      remoteId: "remote1",
    });
    expect(() =>
      store.publications.save({ ...publication, id: "pub2", sessionId: "s2", marker: "other" }),
    ).toThrow();
    expect(() =>
      sql.prepare("UPDATE publications SET target_json = '{}' WHERE id = 'pub1'").run(),
    ).toThrow("immutable");
  });

  it("accepts a standard proposal atomically with its source and version history", () => {
    const { store, sql } = fixture();
    store.experiences.save(
      {
        id: "e1",
        projectId: "p",
        kind: "standard",
        body: "Review callers.",
        status: "draft",
        sourceSessionIds: ["s1"],
        createdAt: fixtureTime,
        updatedAt: fixtureTime,
      },
      { evidence: "The original discussion" },
    );
    const service = new ExperienceService(store);
    service.decide("e1", "accepted");
    expect(store.projects.listStandardVersions("p").map((version) => version.body)).toEqual([
      fixtureProject.standard,
      `${fixtureProject.standard}\n\nReview callers.`,
    ]);
    expect(store.projects.listStandardVersions("p")[1]?.sourceExperienceId).toBe("e1");
    expect(
      sql.prepare("SELECT accepted_standard_version_id FROM experiences WHERE id = 'e1'").get(),
    ).toEqual({ accepted_standard_version_id: "standard:p:2" });
    expect(() => service.decide("e1", "accepted")).toThrow("已经处理");
    expect(() =>
      sql
        .prepare("UPDATE experience_sources SET evidence_json = '{}' WHERE experience_id = 'e1'")
        .run(),
    ).toThrow("immutable");
    expect(() => store.projects.save({ ...fixtureProject, standard: "Overwrite v1" })).toThrow(
      "新版本",
    );
  });

  it("keeps messages in insertion order when timestamps are equal and validates reply ownership", () => {
    const { store } = fixture();
    const user = {
      id: "z-user",
      projectId: "p",
      sessionId: "s1",
      role: "user" as const,
      visibility: "internal" as const,
      body: "Question",
      createdAt: fixtureTime,
    };
    store.messages.save(user);
    store.messages.save({
      ...user,
      id: "a-assistant",
      role: "assistant",
      body: "Answer",
      replyToMessageId: user.id,
    });
    expect(store.messages.listBySession("s1").map((message) => message.id)).toEqual([
      "z-user",
      "a-assistant",
    ]);
    expect(() =>
      store.messages.save({
        ...user,
        id: "cross-session",
        sessionId: "s2",
        replyToMessageId: user.id,
      }),
    ).toThrow("another session");
  });

  it("publishes change notifications only after a successful outer transaction", () => {
    const { store } = fixture();
    const versions: number[] = [];
    store.changes.subscribe((change) => versions.push(change.version));
    expect(() =>
      store.transaction(() => {
        store.models.save({ ...fixtureModel, name: "Discard" });
        throw new Error("rollback");
      }),
    ).toThrow("rollback");
    expect(versions).toHaveLength(0);
    store.transaction(() => {
      store.models.save({ ...fixtureModel, name: "First" });
      store.models.save({ ...fixtureModel, name: "Second" });
    });
    expect(versions).toHaveLength(1);
    expect(store.models.get("m")?.name).toBe("Second");
  });
});
