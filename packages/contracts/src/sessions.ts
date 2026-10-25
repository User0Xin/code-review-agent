import type { Message } from "./messages.js";
import type { PullRequestSnapshot } from "./pull-requests.js";
import type { Finding, ReviewRun } from "./reviews.js";

export interface Session extends PullRequestSnapshot {
  id: string;
  projectId: string;
  ciStatus: string;
  createdAt: string;
  updatedAt: string;
  lastReviewAt?: string;
}

export interface SessionDetail {
  session: Session;
  runs: ReviewRun[];
  findings: Finding[];
  messages: Message[];
}
