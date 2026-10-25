import { z } from "zod";

export const importPrSchema = z.object({
  number: z.coerce.number().int().positive(),
});

export interface ChangedFile {
  path: string;
  oldPath?: string;
  patch: string;
  content?: string;
  status: string;
  additions: number;
  deletions: number;
}

export interface PullRequestSnapshot {
  number: number;
  title: string;
  description: string;
  url: string;
  headSha: string;
  baseSha: string;
  startSha?: string;
  branch: string;
  baseBranch: string;
  state: "open" | "merged" | "closed";
  files: ChangedFile[];
  truncated: boolean;
}

export interface RemoteComment {
  id: string;
  body: string;
  author: string;
  isBot: boolean;
  threadId?: string;
  replyToId?: string;
  path?: string;
  line?: number;
  createdAt: string;
}

export const normalizedEventSchema = z.object({
  id: z.string().min(1),
  type: z.enum(["pr", "reply", "ci", "closed"]),
  repository: z.string(),
  number: z.number().int().positive().optional(),
  headSha: z.string().optional(),
  body: z.string().optional(),
  commentId: z.string().optional(),
  threadId: z.string().optional(),
  author: z.string().optional(),
  isBot: z.boolean().optional(),
  state: z.string().optional(),
});

export type NormalizedEvent = z.infer<typeof normalizedEventSchema>;
