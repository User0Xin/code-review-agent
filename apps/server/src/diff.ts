/** Reads RIGHT/new-side hunk text and positions without treating deleted lines as new content. */
export function newSideLines(
  patch: string,
): Map<number, { oldLine: number | undefined; text: string }> {
  const lines = new Map<number, { oldLine: number | undefined; text: string }>();
  let oldLine = 0;
  let newLine = 0;
  let remainingOld = 0;
  let remainingNew = 0;
  for (const line of patch.split("\n")) {
    const hunk = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(line);
    if (hunk) {
      oldLine = Number(hunk[1]);
      newLine = Number(hunk[3]);
      remainingOld = Number(hunk[2] ?? 1);
      remainingNew = Number(hunk[4] ?? 1);
      continue;
    }
    if (line.startsWith("+") && remainingNew > 0) {
      lines.set(newLine++, { oldLine: undefined, text: line.slice(1) });
      remainingNew--;
    } else if (line.startsWith("-") && remainingOld > 0) {
      oldLine++;
      remainingOld--;
    } else if (line.startsWith(" ") && remainingOld > 0 && remainingNew > 0) {
      lines.set(newLine++, { oldLine: oldLine++, text: line.slice(1) });
      remainingOld--;
      remainingNew--;
    }
  }
  return lines;
}

/** Maps real RIGHT/new-side hunk lines; context lines retain their distinct old line. */
export function newLinePositions(patch: string): Map<number, number | undefined> {
  return new Map([...newSideLines(patch)].map(([line, entry]) => [line, entry.oldLine]));
}

export function diffLines(patch: string): Set<number> {
  return new Set(newSideLines(patch).keys());
}
