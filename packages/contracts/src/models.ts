import { z } from "zod";
import { httpUrl } from "./validation.js";

export const CHATGPT_BASE_URL = "https://api.openai.com/v1";

export const modelInputSchema = z
  .object({
    name: z.string().trim().min(1).max(100),
    authMode: z.enum(["api-key", "chatgpt"]).optional(),
    chatgptAccountId: z.string().trim().min(1).max(100).optional(),
    baseUrl: httpUrl,
    model: z.string().trim().min(1).max(200),
    apiKey: z.string().max(10000).optional(),
    enabled: z.boolean().default(true),
  })
  .superRefine((value, context) => {
    if (value.authMode === "chatgpt") {
      if (!value.chatgptAccountId) {
        context.addIssue({
          code: "custom",
          path: ["chatgptAccountId"],
          message: "请先登录 ChatGPT 账号",
        });
      }
      if (value.baseUrl !== CHATGPT_BASE_URL || value.apiKey) {
        context.addIssue({
          code: "custom",
          path: ["baseUrl"],
          message: "账号连接仅使用 OpenAI 官方接口，无需 API Key",
        });
      }
    } else if (value.chatgptAccountId) {
      context.addIssue({
        code: "custom",
        path: ["chatgptAccountId"],
        message: "API Key 连接不能绑定 ChatGPT 账号",
      });
    }
  });

export type ModelInput = z.infer<typeof modelInputSchema>;

export interface ModelConnection {
  id: string;
  name: string;
  authMode?: "api-key" | "chatgpt";
  chatgptAccountId?: string;
  baseUrl: string;
  model: string;
  enabled: boolean;
  hasApiKey: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ChatGptAccount {
  id: string;
  email: string;
  connected: boolean;
}

export interface ChatGptModel {
  id: string;
  name: string;
}

export interface ChatGptLoginAttempt {
  id: string;
  authorizationUrl?: string;
  status: "pending" | "completed" | "failed" | "cancelled";
  account?: ChatGptAccount;
  error?: string;
  expiresAt: string;
}

export const chatgptLoginInputSchema = z.object({
  accountId: z.string().trim().min(1).max(100).optional(),
});
