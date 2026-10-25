import { reviewResultSchema, type FindingDraft, type ReviewResult } from "@review/contracts";
import picomatch from "picomatch";
import { fingerprint } from "../common/finding-fingerprint.js";
import { diffLines, newSideLines } from "../diff.js";
import type { ReviewInput } from "../ports.js";

export function reviewScope(input: ReviewInput): ReviewInput {
  const isIgnored = picomatch(input.project.ignorePatterns, { dot: true });
  return {
    ...input,
    snapshot: {
      ...input.snapshot,
      files: input.snapshot.files.filter((file) => !isIgnored(file.path)),
    },
  };
}

function matchesEvidence(source: string, evidence: string): boolean {
  const line = source.trim();
  const quote = evidence.trim();
  return (
    line.length >= 6 && (evidence.includes(line) || (quote.length >= 6 && line.includes(quote)))
  );
}

function hasEvidence(draft: FindingDraft, input: ReviewInput): boolean {
  const file = input.snapshot.files.find((candidate) => candidate.path === draft.path);
  if (!file?.patch.trim()) {
    return false;
  }
  if (draft.line !== null && !diffLines(file.patch).has(draft.line)) {
    return false;
  }
  const sources = [
    file.patch
      .split("\n")
      .filter(
        (line) => !line.startsWith("@@") && !line.startsWith("+++") && !line.startsWith("---"),
      )
      .map((line) => (/^[+ -]/.test(line) ? line.slice(1) : line)),
    (file.content ?? "").split("\n"),
    input.project.standard.split("\n"),
  ].flat();
  return sources.some((line) => matchesEvidence(line, draft.evidence));
}

function locateFinding(draft: FindingDraft, input: ReviewInput): FindingDraft {
  if (draft.line === null) {
    return draft;
  }
  const file = input.snapshot.files.find((candidate) => candidate.path === draft.path);
  const matches = [...newSideLines(file?.patch ?? "")]
    .filter(([, entry]) => matchesEvidence(entry.text, draft.evidence))
    .map(([line]) => line);
  if (matches.includes(draft.line)) {
    return draft;
  }
  return { ...draft, line: matches.length === 1 ? (matches[0] ?? null) : null };
}

export function validateReviewResult(
  input: ReviewInput,
  scoped: ReviewInput,
  output: unknown,
): { result: ReviewResult; coverage: string } {
  const candidate = reviewResultSchema.parse(output);
  const unique = new Map<string, FindingDraft>();
  let correctedLocations = 0;
  let fileLevelLocations = 0;
  for (const draft of candidate.findings) {
    if (!hasEvidence(draft, scoped)) {
      continue;
    }
    const located = locateFinding(draft, scoped);
    const key = fingerprint(located);
    if (!unique.has(key)) {
      unique.set(key, located);
      if (located.line !== draft.line) {
        if (located.line === null) {
          fileLevelLocations++;
        } else {
          correctedLocations++;
        }
      }
    }
  }
  const valid = [...unique.values()];
  const active = new Set(unique.keys());
  const resolvable = new Set(
    input.priorFindings
      .filter((finding) => {
        const file = scoped.snapshot.files.find(
          (candidate) => candidate.path === finding.path || candidate.oldPath === finding.path,
        );
        return (
          finding.status === "open" &&
          !input.snapshot.truncated &&
          file?.patch.trim() &&
          (file.status === "removed" ||
            Boolean(file.content?.trim()) ||
            (finding.line !== null && diffLines(file.patch).has(finding.line))) &&
          !active.has(finding.fingerprint)
        );
      })
      .map((finding) => finding.id),
  );
  const resolvedFindingIds = [
    ...new Set(candidate.resolvedFindingIds.filter((id) => resolvable.has(id))),
  ];
  const omitted = input.snapshot.files.length - scoped.snapshot.files.length;
  const unavailable = scoped.snapshot.files.filter((file) => !file.patch.trim()).length;
  const skipped = candidate.findings.length - valid.length;
  const partial =
    input.snapshot.truncated ||
    unavailable > 0 ||
    omitted > 0 ||
    skipped > 0 ||
    fileLevelLocations > 0;
  const coverage = [
    `${partial ? "部分覆盖" : "完整覆盖返回的变更"}：${scoped.snapshot.files.length} 个文件`,
    `忽略 ${omitted} 个`,
    `缺少 diff ${unavailable} 个`,
    `拒绝无有效依据或定位的发现 ${skipped} 个`,
    `未采纳范围外的解决结论 ${candidate.resolvedFindingIds.length - resolvedFindingIds.length} 个`,
    ...(correctedLocations > 0 ? [`根据原文证据纠正行号 ${correctedLocations} 个`] : []),
    ...(fileLevelLocations > 0 ? [`无法唯一定位 ${fileLevelLocations} 个，已按文件级保留`] : []),
    ...(input.snapshot.truncated ? ["平台返回被截断，未审查全部变更"] : []),
  ].join("；");
  return { result: { summary: candidate.summary, findings: valid, resolvedFindingIds }, coverage };
}
