import type { ChangedFile } from "@review/contracts";
import { ChevronRight, FileCode2 } from "lucide-react";
import { useMemo } from "react";
import { Diff, Hunk, parseDiff } from "react-diff-view";
import "react-diff-view/style/index.css";
export default function DiffViewer({ files }: { files: ChangedFile[] }) {
  return (
    <div className="diff-list">
      {!files.length ? (
        <p className="muted">当前 PR 没有可展示的文件改动。</p>
      ) : (
        files.map((file) => <FileDiff key={file.path} file={file} />)
      )}
    </div>
  );
}
function FileDiff({ file }: { file: ChangedFile }) {
  const parsed = useMemo(() => {
    if (!file.patch) {
      return [];
    }
    try {
      return parseDiff(
        file.patch.startsWith("diff --git")
          ? file.patch
          : `diff --git a/${file.oldPath || file.path} b/${file.path}\n--- a/${file.oldPath || file.path}\n+++ b/${file.path}\n${file.patch}\n`,
      );
    } catch {
      return [];
    }
  }, [file]);
  return (
    <details className="file-diff" open>
      <summary>
        <ChevronRight size={14} className="file-diff-caret" aria-hidden="true" />
        <FileCode2 size={14} aria-hidden="true" />
        <code>{file.path}</code>
        <span className="diff-add">+{file.additions}</span>
        <span className="diff-remove">−{file.deletions}</span>
      </summary>
      <div className="diff-content">
        {parsed.length ? (
          parsed.map((diff, i) => (
            <Diff key={i} viewType="unified" diffType={diff.type} hunks={diff.hunks}>
              {(hunks) => hunks.map((hunk) => <Hunk key={hunk.content} hunk={hunk} />)}
            </Diff>
          ))
        ) : (
          <p className="muted">
            {file.patch
              ? "该文件的差异格式无法展示，请在仓库平台查看。"
              : "该文件没有文本差异（可能为二进制文件或已被忽略）。"}
          </p>
        )}
      </div>
    </details>
  );
}
