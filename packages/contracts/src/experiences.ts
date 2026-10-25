import { z } from "zod";

export const experienceDecisionSchema = z.object({
  status: z.enum(["accepted", "rejected"]),
});

export interface Experience {
  id: string;
  projectId: string;
  kind: "experience" | "standard";
  body: string;
  sourceSessionIds: string[];
  status: "draft" | "accepted" | "rejected";
  createdAt: string;
  updatedAt: string;
}
