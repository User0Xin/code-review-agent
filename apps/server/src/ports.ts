import type {
  Experience,
  Finding,
  ModelConnection,
  NormalizedEvent,
  Project,
  Provider,
  PullRequestSnapshot,
  RemoteComment,
  ReviewResult,
} from "@review/contracts";

export interface RepositoryPort {
  getRevision?(
    project: Project,
    token: string,
    number: number,
  ): Promise<Pick<PullRequestSnapshot, "headSha" | "baseSha" | "startSha" | "state">>;
  getPullRequest(project: Project, token: string, number: number): Promise<PullRequestSnapshot>;
  listPullRequests(
    project: Project,
    token: string,
  ): Promise<Array<Pick<PullRequestSnapshot, "number" | "title" | "url" | "headSha">>>;
  getComments(project: Project, token: string, number: number): Promise<RemoteComment[]>;
  publishComment(
    project: Project,
    token: string,
    snapshot: PullRequestSnapshot,
    comment: {
      body: string;
      path?: string;
      line?: number;
      replyToId?: string;
      threadId?: string;
    },
  ): Promise<RemoteComment>;
}
export interface ReviewInput {
  project: Project;
  snapshot: PullRequestSnapshot;
  priorFindings: Finding[];
  experiences: Experience[];
  model: ModelConnection;
  apiKey: string;
}
export interface AgentPort {
  review(input: ReviewInput): Promise<ReviewResult>;
  reply(
    input: ReviewInput & { finding?: Finding; message: string },
  ): Promise<{ body: string; verdict: "keep" | "withdraw" | "uncertain" }>;
  chat(
    input: ReviewInput & {
      message: string;
      history: Array<{ role: "user" | "assistant"; body: string }>;
    },
  ): Promise<string>;
  summarize(input: {
    project: Project;
    model: ModelConnection;
    apiKey: string;
    evidence: string;
    sourceSessionIds: string[];
  }): Promise<Array<{ kind: "experience" | "standard"; body: string }>>;
  test(model: ModelConnection, apiKey: string): Promise<string>;
}
export interface WebhookPort {
  verify(
    provider: Provider,
    headers: Record<string, string | undefined>,
    rawBody: Buffer,
    secret: string,
  ): boolean;
  normalize(
    provider: Provider,
    headers: Record<string, string | undefined>,
    payload: unknown,
  ): NormalizedEvent[];
}
