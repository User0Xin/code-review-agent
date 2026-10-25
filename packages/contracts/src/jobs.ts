import { z } from "zod";
import { normalizedEventSchema } from "./pull-requests.js";

const jobFields = {
  id: z.string(),
  key: z.string(),
  status: z.enum(["queued", "running", "completed", "failed"]),
  attempts: z.number().int().nonnegative(),
  maxAttempts: z.number().int().positive(),
  availableAt: z.number(),
  lockedAt: z.number().nullable(),
  error: z.string().nullable(),
  createdAt: z.number(),
};

export const jobSchema = z.discriminatedUnion("kind", [
  z.object({
    ...jobFields,
    kind: z.literal("review"),
    payload: z.object({ sessionId: z.string().min(1), trigger: z.string().optional() }),
  }),
  z.object({
    ...jobFields,
    kind: z.literal("publish"),
    payload: z.object({ runId: z.string().min(1) }),
  }),
  z.object({
    ...jobFields,
    kind: z.literal("chat"),
    payload: z.object({
      sessionId: z.string().min(1),
      body: z.string(),
      findingId: z.string().optional(),
      mode: z.literal("publish-user").optional(),
    }),
  }),
  z.object({
    ...jobFields,
    kind: z.literal("reply"),
    payload: z.discriminatedUnion("mode", [
      z.object({
        mode: z.literal("publish-user"),
        sessionId: z.string().min(1),
        body: z.string(),
        findingId: z.string().optional(),
      }),
      z.object({
        mode: z.literal("feedback"),
        sessionId: z.string().min(1),
        body: z.string(),
        findingId: z.string().min(1),
        sourceMessageId: z.string().min(1),
      }),
    ]),
  }),
  z.object({
    ...jobFields,
    kind: z.literal("summary"),
    payload: z.object({ projectId: z.string().min(1) }),
  }),
  z.object({
    ...jobFields,
    kind: z.literal("event"),
    payload: z.object({ projectId: z.string().min(1), event: normalizedEventSchema }),
  }),
]);

export type Job = z.infer<typeof jobSchema>;
export type JobKind = Job["kind"];
export type JobOfKind<K extends JobKind> = Extract<Job, { kind: K }>;
export type EnqueueArguments = {
  [K in JobKind]: [kind: K, payload: JobOfKind<K>["payload"], key: string];
}[JobKind];
