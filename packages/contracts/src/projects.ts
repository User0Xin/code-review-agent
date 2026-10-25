import { z } from "zod";
import { httpUrl } from "./validation.js";

export const providerSchema = z.enum(["github", "gitlab"]);

export type Provider = z.infer<typeof providerSchema>;

export const projectInputSchema = z.object({
  name: z.string().trim().min(1).max(100),
  provider: providerSchema,
  host: httpUrl,
  repository: z
    .string()
    .trim()
    .regex(/^[\w.-]+(?:\/[\w.-]+)+$/, "填写 owner/repo 或 group/repo"),
  modelId: z.string().min(1),
  token: z.string().max(10000).optional(),
  webhookSecret: z.string().max(10000).optional(),
  standard: z.string().max(50000).default(""),
  ignorePatterns: z
    .array(z.string().max(300))
    .max(100)
    .default(["**/node_modules/**", "**/dist/**", "**/*lock*"]),
  autoReview: z.boolean().default(true),
  autoPublish: z.boolean().default(false),
});

export type ProjectInput = z.infer<typeof projectInputSchema>;

export interface Project {
  id: string;
  name: string;
  provider: Provider;
  host: string;
  repository: string;
  modelId: string;
  standard: string;
  standardVersion: number;
  ignorePatterns: string[];
  autoReview: boolean;
  autoPublish: boolean;
  hasToken: boolean;
  hasWebhookSecret: boolean;
  createdAt: string;
  updatedAt: string;
}
