import type { FindingDraft } from "@review/contracts";
import { describe, expect, it } from "vitest";
import type { ReviewInput } from "../src/ports.js";
import { validateReviewResult } from "../src/workflow/review-validation.js";
import { fixtureModel, fixtureProject, fixtureSnapshot } from "./fixtures/stored-workspace.js";

const stock = `export interface InventoryItem {
  id: string;
  available: number;
}

/** Allow reservations that exactly exhaust the available stock. */
export function canReserve(item: InventoryItem, quantity: number): boolean {
  if (!Number.isInteger(quantity) || quantity <= 0) {
    return false;
  }
  return item.available > quantity;
}

/** Return a new inventory value with the reserved quantity deducted. */
export function reserve(item: InventoryItem, quantity: number): InventoryItem {
  if (!canReserve(item, quantity)) {
    throw new Error("Insufficient stock or invalid quantity");
  }

  return {
    ...item,
    available: item.available + quantity,
  };
}`;

const draft: FindingDraft = {
  title: "Stock reservation increases available inventory",
  body: "Reserving two items from five should leave three available, rather than seven.",
  path: "src/stock.ts",
  line: 20,
  severity: "medium",
  category: "bug",
  evidence: "available: item.available + quantity,",
};

function validate(patch: string, findings: FindingDraft[], content?: string) {
  const input: ReviewInput = {
    project: fixtureProject,
    model: fixtureModel,
    apiKey: "",
    priorFindings: [],
    experiences: [],
    snapshot: {
      ...fixtureSnapshot,
      files: [{ path: draft.path, patch, content, status: "modified", additions: 1, deletions: 1 }],
    },
  };
  return validateReviewResult(input, input, {
    summary: "Review completed",
    findings,
    resolvedFindingIds: [],
  });
}

describe("review evidence locations", () => {
  it("anchors the stock findings to lines 11 and 22 instead of unrelated diff lines 9 and 20", () => {
    const patch = `@@ -0,0 +1,24 @@\n${stock
      .split("\n")
      .map((line) => `+${line}`)
      .join("\n")}`;
    const { result } = validate(patch, [
      {
        ...draft,
        title: "An exact stock reservation is rejected",
        line: 9,
        evidence: "return item.available > quantity;",
      },
      draft,
    ]);

    expect(result.findings.map((finding) => finding.line)).toEqual([11, 22]);
  });

  it("uses new-side hunk numbers after deletions and retains a matching context line", () => {
    const patch = `@@ -40,4 +70,4 @@
 function calculate() {
-  return inventory.available - quantity;
+  const next = inventory.available + quantity;
   return next;
 }`;
    const { result } = validate(patch, [
      { ...draft, line: 72, evidence: "const next = inventory.available + quantity;" },
      { ...draft, title: "Context evidence", line: 72, evidence: "return next;" },
    ]);

    expect(result.findings.map((finding) => finding.line)).toEqual([71, 72]);
  });

  it("keeps ambiguous repeated evidence as a file-level finding instead of guessing a nearby line", () => {
    const patch = `@@ -0,0 +5,3 @@
+  return inventory.available;
+  const quantity = 2;
+  return inventory.available;`;
    const { result, coverage } = validate(patch, [
      { ...draft, line: 6, evidence: "return inventory.available;" },
    ]);

    expect(result.findings).toHaveLength(1);
    expect(result.findings[0]?.line).toBeNull();
    expect(coverage).toContain("文件级");
  });

  it("retains the chosen evidence line when the same statement appears elsewhere", () => {
    const patch = `@@ -0,0 +5,3 @@
+  return inventory.available;
+  const quantity = 2;
+  return inventory.available;`;
    const { result } = validate(patch, [
      { ...draft, line: 7, evidence: "return inventory.available;" },
    ]);

    expect(result.findings[0]?.line).toBe(7);
  });

  it("keeps old-side evidence without attaching it to a different new-side statement", () => {
    const patch = `@@ -40,3 +70,3 @@
 function calculate() {
-  return inventory.available - quantity;
+  return inventory.available + quantity;
 }`;
    const { result } = validate(patch, [
      { ...draft, line: 71, evidence: "return inventory.available - quantity;" },
    ]);

    expect(result.findings).toHaveLength(1);
    expect(result.findings[0]?.line).toBeNull();
  });

  it("retains valid standard evidence without using annotated content to infer a source line", () => {
    const patch = "@@ -0,0 +70,1 @@\n+  const ready = true;";
    const excerpt = "\n[文件第 70 行起]\nreturn inventory.available + quantity;\n";
    const { result } = validate(
      patch,
      [
        { ...draft, line: 70, category: "standard", evidence: fixtureProject.standard },
        {
          ...draft,
          title: "Excerpt evidence",
          line: 70,
          evidence: "return inventory.available + quantity;",
        },
      ],
      excerpt,
    );

    expect(result.findings).toHaveLength(2);
    expect(result.findings.map((finding) => finding.line)).toEqual([null, null]);
  });

  it("preserves an explicitly file-level finding even when its evidence has a unique line", () => {
    const patch = "@@ -0,0 +70,1 @@\n+  available: item.available + quantity,";
    const { result } = validate(patch, [{ ...draft, line: null }]);

    expect(result.findings[0]?.line).toBeNull();
  });
});
