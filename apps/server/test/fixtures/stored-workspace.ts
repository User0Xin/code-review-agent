import type { ModelConnection, Project, PullRequestSnapshot } from "@review/contracts";
import type { Store } from "../../src/store.js";

export const fixtureTime = "2026-10-22T00:00:00.000Z";
export const fixtureModel: ModelConnection = {
  id: "m",
  name: "Model",
  baseUrl: "https://models.test/v1",
  model: "test",
  enabled: true,
  hasApiKey: false,
  createdAt: fixtureTime,
  updatedAt: fixtureTime,
};
export const fixtureProject: Project = {
  id: "p",
  name: "Project",
  provider: "github",
  host: "https://github.com",
  repository: "team/repo",
  modelId: "m",
  standard: "Check input.",
  standardVersion: 1,
  ignorePatterns: [],
  autoReview: true,
  autoPublish: false,
  hasToken: false,
  hasWebhookSecret: false,
  createdAt: fixtureTime,
  updatedAt: fixtureTime,
};
export const fixtureSnapshot: PullRequestSnapshot = {
  number: 1,
  title: "Change",
  description: "A change",
  url: "https://github.com/team/repo/pull/1",
  headSha: "head1",
  baseSha: "base1",
  branch: "feature",
  baseBranch: "main",
  state: "open",
  files: [],
  truncated: false,
};

export function seedWorkspace(store: Store): void {
  store.models.save(fixtureModel);
  store.projects.save(fixtureProject);
  for (const [index, id] of ["s1", "s2"].entries()) {
    store.sessions.save({
      ...fixtureSnapshot,
      id,
      projectId: "p",
      number: index + 1,
      ciStatus: "unknown",
      createdAt: fixtureTime,
      updatedAt: fixtureTime,
    });
  }
}
