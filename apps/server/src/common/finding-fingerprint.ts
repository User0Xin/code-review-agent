import type { Finding } from "@review/contracts";
import { createHash } from "node:crypto";

const normalized = (value: string) => value.trim().toLowerCase().replace(/\s+/g, " ");

export const fingerprint = (finding: Pick<Finding, "path" | "category" | "title" | "line">) =>
  createHash("sha256")
    .update(
      `${finding.path}\0${finding.category}\0${finding.line ?? "overview"}\0${normalized(finding.title)}`,
    )
    .digest("hex");
