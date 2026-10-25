import { z } from "zod";

export const messageInputSchema = z.object({
  body: z.string().trim().min(1).max(16000),
  visibility: z.enum(["internal", "external"]).default("internal"),
  findingId: z.string().optional(),
});

export interface Message {
  id: string;
  projectId: string;
  sessionId: string;
  role: "user" | "assistant" | "system";
  body: string;
  visibility: "internal" | "external";
  findingId?: string;
  remoteId?: string;
  remoteCommentKind?: string;
  replyToMessageId?: string;
  createdAt: string;
}
