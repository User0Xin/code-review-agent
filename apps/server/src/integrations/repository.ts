import type { ChangedFile, Project, PullRequestSnapshot, RemoteComment } from "@review/contracts";
import { newLinePositions } from "../diff.js";
import type { RepositoryPort } from "../ports.js";
import {
  readArray,
  readId,
  readNumber,
  readObject,
  readPositiveNumber,
  readString,
  type PlatformObject,
} from "./platform-values.js";
import { RepositoryClient, type RepositoryClientOptions } from "./repository-client.js";
export { newLinePositions } from "../diff.js";

import { IntegrationError } from "./integration-error.js";

export interface RepositoryAdapterOptions extends RepositoryClientOptions {
  maxFiles?: number;
  maxPatchChars?: number;
  maxContextChars?: number;
}

export class HttpRepositoryAdapter implements RepositoryPort {
  private readonly client: RepositoryClient;
  private readonly maxFiles: number;
  private readonly maxPatchChars: number;
  private readonly maxContextChars: number;

  constructor(options: RepositoryAdapterOptions = {}) {
    this.client = new RepositoryClient(options);
    this.maxFiles = options.maxFiles ?? 100;
    this.maxPatchChars = options.maxPatchChars ?? 180_000;
    this.maxContextChars = options.maxContextChars ?? 120_000;
  }

  async listPullRequests(project: Project, token: string) {
    const list = await this.client.pages(
      project,
      token,
      project.provider === "github"
        ? "/pulls?state=open&sort=updated&direction=desc"
        : "/merge_requests?state=opened&order_by=updated_at&sort=desc",
    );
    return list.map((pr) => ({
      number: readNumber(project.provider === "github" ? pr.number : pr.iid),
      title: readString(pr.title),
      url: readString(project.provider === "github" ? pr.html_url : pr.web_url),
      headSha:
        project.provider === "github" ? readString(readObject(pr.head).sha) : readString(pr.sha),
    }));
  }

  /** Lightweight publication freshness check: no files, context or comment pagination. */
  async getRevision(
    project: Project,
    token: string,
    number: number,
  ): Promise<Pick<PullRequestSnapshot, "headSha" | "baseSha" | "startSha" | "state">> {
    if (!Number.isInteger(number) || number < 1) {
      throw new IntegrationError("合并请求编号无效");
    }
    const path = project.provider === "github" ? `/pulls/${number}` : `/merge_requests/${number}`;
    return this.revision(
      project,
      readObject((await this.client.request(project, token, path)).value),
    );
  }

  private revision(
    project: Project,
    pr: PlatformObject,
  ): Pick<PullRequestSnapshot, "headSha" | "baseSha" | "startSha" | "state"> {
    if (project.provider === "github") {
      const headSha = readString(readObject(pr.head).sha);
      const baseSha = readString(readObject(pr.base).sha);
      if (!headSha || !baseSha) {
        throw new IntegrationError("合并请求缺少提交版本，无法安全审查", true);
      }
      return {
        headSha,
        baseSha,
        state: pr.merged === true ? "merged" : pr.state === "closed" ? "closed" : "open",
      };
    }
    const refs = pr.diff_refs && typeof pr.diff_refs === "object" ? readObject(pr.diff_refs) : {};
    const headSha = readString(refs.head_sha);
    const baseSha = readString(refs.base_sha);
    const startSha = readString(refs.start_sha);
    if (!headSha || !baseSha || !startSha) {
      throw new IntegrationError("GitLab diff 尚未就绪，请稍后重试", true);
    }
    return {
      headSha,
      baseSha,
      startSha,
      state: pr.state === "merged" ? "merged" : pr.state === "closed" ? "closed" : "open",
    };
  }

  async getPullRequest(
    project: Project,
    token: string,
    number: number,
  ): Promise<PullRequestSnapshot> {
    if (!Number.isInteger(number) || number < 1) {
      throw new IntegrationError("合并请求编号无效");
    }
    const route = project.provider === "github" ? `/pulls/${number}` : `/merge_requests/${number}`;
    const pr = readObject((await this.client.request(project, token, route)).value);
    // Fork PRs may require reading the immutable blob from the source repository.
    const sourceRepository =
      project.provider === "github"
        ? readString(readObject(pr.head).repo ? readObject(readObject(pr.head).repo).full_name : "")
        : readId(pr.source_project_id);
    const contentProject = sourceRepository
      ? { ...project, repository: sourceRepository }
      : project;
    let snapshot: PullRequestSnapshot;
    if (project.provider === "github") {
      snapshot = {
        number,
        title: readString(pr.title),
        description: readString(pr.body),
        url: readString(pr.html_url),
        headSha: readString(readObject(pr.head).sha),
        baseSha: readString(readObject(pr.base).sha),
        branch: readString(readObject(pr.head).ref),
        baseBranch: readString(readObject(pr.base).ref),
        state: pr.merged === true ? "merged" : pr.state === "closed" ? "closed" : "open",
        files: [],
        truncated: false,
      };
    } else {
      const refs = pr.diff_refs && typeof pr.diff_refs === "object" ? readObject(pr.diff_refs) : {};
      if (!readString(refs.head_sha) || !readString(refs.base_sha) || !readString(refs.start_sha)) {
        throw new IntegrationError("GitLab diff 尚未就绪，请稍后重试", true);
      }
      snapshot = {
        number,
        title: readString(pr.title),
        description: readString(pr.description),
        url: readString(pr.web_url),
        headSha: readString(refs.head_sha),
        baseSha: readString(refs.base_sha),
        startSha: readString(refs.start_sha),
        branch: readString(pr.source_branch),
        baseBranch: readString(pr.target_branch),
        state: pr.state === "merged" ? "merged" : pr.state === "closed" ? "closed" : "open",
        files: [],
        truncated: false,
      };
    }
    if (!snapshot.headSha || !snapshot.baseSha) {
      throw new IntegrationError("合并请求缺少提交版本，无法安全审查", true);
    }
    const remoteFiles = await this.client.pages(
      project,
      token,
      `${route}/${project.provider === "github" ? "files" : "diffs"}`,
    );
    const expectedCount =
      project.provider === "github"
        ? readNumber(pr.changed_files)
        : Number.parseInt(readString(pr.changes_count), 10);
    if (
      remoteFiles.length > this.maxFiles ||
      expectedCount > remoteFiles.length ||
      readString(pr.changes_count).endsWith("+")
    ) {
      snapshot.truncated = true;
    }
    let patchBudget = this.maxPatchChars;
    let contextBudget = this.maxContextChars;
    for (const remote of remoteFiles.slice(0, this.maxFiles)) {
      const github = project.provider === "github";
      const originalPatch = readString(github ? remote.patch : remote.diff);
      const patch = originalPatch.slice(0, Math.max(0, Math.min(30_000, patchBudget)));
      patchBudget -= patch.length;
      if (
        patch.length < originalPatch.length ||
        !originalPatch ||
        remote.too_large === true ||
        remote.collapsed === true
      ) {
        snapshot.truncated = true;
      }
      const file: ChangedFile = {
        path: readString(github ? remote.filename : remote.new_path),
        oldPath: readString(github ? remote.previous_filename : remote.old_path) || undefined,
        status: github
          ? readString(remote.status)
          : remote.deleted_file
            ? "removed"
            : remote.new_file
              ? "added"
              : remote.renamed_file
                ? "renamed"
                : "modified",
        patch,
        additions: github
          ? readNumber(remote.additions)
          : originalPatch
              .split("\n")
              .filter((line) => line.startsWith("+") && !line.startsWith("+++")).length,
        deletions: github
          ? readNumber(remote.deletions)
          : originalPatch
              .split("\n")
              .filter((line) => line.startsWith("-") && !line.startsWith("---")).length,
      };
      if (!file.path) {
        throw new IntegrationError("平台 diff 缺少文件路径");
      }
      if (file.status !== "removed" && patch && contextBudget > 0) {
        const contentRoute = github
          ? `/contents/${file.path.split("/").map(encodeURIComponent).join("/")}?ref=${encodeURIComponent(snapshot.headSha)}`
          : `/repository/files/${encodeURIComponent(file.path)}?ref=${encodeURIComponent(snapshot.headSha)}`;
        try {
          const blob = readObject(
            (await this.client.request(contentProject, token, contentRoute)).value,
          );
          if (blob.encoding === "base64" && typeof blob.content === "string") {
            const raw = Buffer.from(blob.content, "base64").toString("utf8");
            if (!raw.includes("\0")) {
              const excerpt = surroundingContent(raw, patch, Math.min(12_000, contextBudget));
              file.content = excerpt.content;
              contextBudget -= excerpt.content.length;
              if (excerpt.truncated) {
                snapshot.truncated = true;
              }
            } else {
              snapshot.truncated = true;
            }
          } else {
            snapshot.truncated = true;
          }
        } catch (error) {
          if (error instanceof IntegrationError && error.status === 404) {
            snapshot.truncated = true;
          } else {
            throw error;
          }
        }
      } else if (file.status !== "removed" && contextBudget <= 0) {
        snapshot.truncated = true;
      }
      snapshot.files.push(file);
    }
    // The files endpoint is not commit-pinned; fail rather than mixing two revisions.
    const after = await this.getRevision(project, token, number);
    if (
      after.headSha !== snapshot.headSha ||
      after.baseSha !== snapshot.baseSha ||
      after.startSha !== snapshot.startSha
    ) {
      throw new IntegrationError("读取期间合并请求版本发生变化，请重试", true);
    }
    // Closing/merging does not necessarily change the commit refs. Preserve the latest state.
    snapshot.state = after.state;
    return snapshot;
  }

  async getComments(project: Project, token: string, number: number): Promise<RemoteComment[]> {
    if (project.provider === "github") {
      const [timeline, reviews] = await Promise.all([
        this.client.pages(project, token, `/issues/${number}/comments`),
        this.client.pages(project, token, `/pulls/${number}/comments`),
      ]);
      return [
        ...timeline.map((comment) => this.githubComment(comment, false)),
        ...reviews.map((comment) => this.githubComment(comment, true)),
      ].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    }
    const discussions = await this.client.pages(
      project,
      token,
      `/merge_requests/${number}/discussions`,
    );
    const result: RemoteComment[] = [];
    for (const discussion of discussions) {
      const notes = readArray(discussion.notes).map(readObject);
      for (const note of notes) {
        if (note.system === true) {
          continue;
        }
        result.push(
          this.gitlabComment(
            note,
            readString(discussion.id),
            notes[0] && readId(notes[0].id) !== readId(note.id)
              ? `note:${readId(notes[0].id)}`
              : undefined,
          ),
        );
        if (result.length > this.client.itemLimit) {
          throw new IntegrationError("讨论数量超过读取上限；未取得完整历史，已停止操作");
        }
      }
    }
    return result.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }

  async publishComment(
    project: Project,
    token: string,
    snapshot: PullRequestSnapshot,
    comment: {
      body: string;
      path?: string;
      line?: number;
      replyToId?: string;
      threadId?: string;
    },
  ): Promise<RemoteComment> {
    if (!comment.body.trim()) {
      throw new IntegrationError("评论正文不能为空");
    }
    if (project.provider === "github") {
      if (comment.replyToId || comment.threadId) {
        const target = comment.threadId ?? comment.replyToId ?? "";
        if (/^review:\d+$/.test(target)) {
          const all = await this.getComments(project, token, snapshot.number);
          const found = all.find((item) => item.id === (comment.replyToId ?? target));
          if (!found || !found.threadId?.startsWith("review:")) {
            throw new IntegrationError("目标审查线程不存在于当前 PR");
          }
          if (comment.threadId && found.threadId !== comment.threadId) {
            throw new IntegrationError("回复目标与 GitHub 审查线程不匹配");
          }
          const root = found.threadId.slice("review:".length);
          const value = readObject(
            (
              await this.client.request(
                project,
                token,
                `/pulls/${snapshot.number}/comments/${root}/replies`,
                "POST",
                { body: comment.body },
              )
            ).value,
          );
          return this.githubComment(value, true);
        }
        if (/^issue:\d+$/.test(target)) {
          const all = await this.getComments(project, token, snapshot.number);
          if (!all.some((item) => item.id === target)) {
            throw new IntegrationError("目标评论不存在于当前 PR");
          }
          const body = `[回复原评论](${snapshot.url}#issuecomment-${target.slice(6)})\n\n${comment.body}`;
          return {
            ...this.githubComment(
              readObject(
                (
                  await this.client.request(
                    project,
                    token,
                    `/issues/${snapshot.number}/comments`,
                    "POST",
                    { body },
                  )
                ).value,
              ),
              false,
            ),
            replyToId: target,
          };
        }
        throw new IntegrationError("GitHub 回复目标无效，需要明确的评论类型");
      }
      if (comment.line !== undefined) {
        this.linePosition(snapshot, comment.path, comment.line);
        return this.githubComment(
          readObject(
            (
              await this.client.request(
                project,
                token,
                `/pulls/${snapshot.number}/comments`,
                "POST",
                {
                  body: comment.body,
                  commit_id: snapshot.headSha,
                  path: comment.path,
                  line: comment.line,
                  side: "RIGHT",
                },
              )
            ).value,
          ),
          true,
        );
      }
      return this.githubComment(
        readObject(
          (
            await this.client.request(
              project,
              token,
              `/issues/${snapshot.number}/comments`,
              "POST",
              {
                body: comment.body,
              },
            )
          ).value,
        ),
        false,
      );
    }
    const base = `/merge_requests/${snapshot.number}/discussions`;
    if (comment.replyToId || comment.threadId) {
      const all = await this.getComments(project, token, snapshot.number);
      const target = comment.replyToId
        ? all.find((item) => item.id === comment.replyToId)
        : all.find((item) => item.threadId === comment.threadId);
      const thread = comment.threadId ?? target?.threadId;
      if (!target || !thread || target.threadId !== thread) {
        throw new IntegrationError("回复目标与 GitLab 讨论不匹配");
      }
      const note = readObject(
        (
          await this.client.request(
            project,
            token,
            `${base}/${encodeURIComponent(thread)}/notes`,
            "POST",
            { body: comment.body },
          )
        ).value,
      );
      return this.gitlabComment(note, thread, comment.replyToId);
    }
    let position: PlatformObject | undefined;
    if (comment.line !== undefined) {
      const mapped = this.linePosition(snapshot, comment.path, comment.line);
      if (!snapshot.startSha || !snapshot.baseSha || !snapshot.headSha) {
        throw new IntegrationError("GitLab diff 引用尚未就绪", true);
      }
      position = {
        position_type: "text",
        base_sha: snapshot.baseSha,
        start_sha: snapshot.startSha,
        head_sha: snapshot.headSha,
        new_path: mapped.file.path,
        old_path: mapped.file.oldPath ?? mapped.file.path,
        new_line: comment.line,
        ...(mapped.oldLine === undefined ? {} : { old_line: mapped.oldLine }),
      };
    }
    const discussion = readObject(
      (
        await this.client.request(project, token, base, "POST", {
          body: comment.body,
          ...(position ? { position } : {}),
        })
      ).value,
    );
    const notes = readArray(discussion.notes);
    if (!notes[0] || !readString(discussion.id)) {
      throw new IntegrationError("GitLab 发布响应缺少讨论标识");
    }
    return this.gitlabComment(readObject(notes[0]), readString(discussion.id));
  }

  private linePosition(snapshot: PullRequestSnapshot, path: string | undefined, line: number) {
    const file = snapshot.files.find((candidate) => candidate.path === path);
    if (!file || !Number.isInteger(line) || line <= 0) {
      throw new IntegrationError("行内评论位置无效");
    }
    const mapped = newLinePositions(file.patch);
    if (!mapped.has(line)) {
      throw new IntegrationError("目标行不在本次 diff 新文件侧，禁止猜测评论位置");
    }
    return { file, oldLine: mapped.get(line) };
  }

  private githubComment(value: PlatformObject, review: boolean): RemoteComment {
    const user = value.user ? readObject(value.user) : {};
    if (!readId(value.id) || typeof value.body !== "string") {
      throw new IntegrationError("GitHub 评论缺少标识或正文，无法核对发布记录");
    }
    return {
      id: `${review ? "review" : "issue"}:${readId(value.id)}`,
      body: readString(value.body),
      author: readString(user.login),
      isBot: user.type === "Bot" || readString(user.login).endsWith("[bot]"),
      ...(review
        ? {
            threadId: `review:${readId(value.in_reply_to_id) || readId(value.id)}`,
            replyToId: readId(value.in_reply_to_id)
              ? `review:${readId(value.in_reply_to_id)}`
              : undefined,
            path: readString(value.path) || undefined,
            line: readPositiveNumber(value.line),
          }
        : {}),
      createdAt: readString(value.created_at),
    };
  }

  private gitlabComment(value: PlatformObject, thread: string, replyToId?: string): RemoteComment {
    const user = value.author ? readObject(value.author) : {};
    const position = value.position ? readObject(value.position) : {};
    if (!readId(value.id) || !thread || typeof value.body !== "string") {
      throw new IntegrationError("GitLab 评论缺少标识或正文，无法核对发布记录");
    }
    return {
      id: `note:${readId(value.id)}`,
      threadId: thread,
      replyToId,
      body: readString(value.body),
      author: readString(user.username),
      isBot: user.bot === true,
      path: readString(position.new_path) || undefined,
      line: readPositiveNumber(position.new_line),
      createdAt: readString(value.created_at),
    };
  }
}

function surroundingContent(
  content: string,
  patch: string,
  maxChars: number,
): { content: string; truncated: boolean } {
  if (content.length <= maxChars) {
    return { content, truncated: false };
  }
  const lines = content.split("\n");
  const wanted = new Set<number>();
  for (const number of newLinePositions(patch).keys()) {
    for (
      let index = Math.max(0, number - 13);
      index < Math.min(lines.length, number + 12);
      index++
    ) {
      wanted.add(index);
    }
  }
  let result = "";
  let previous = -2;
  let clipped = false;
  for (const index of [...wanted].sort((a, b) => a - b)) {
    const next = `${index !== previous + 1 ? `\n[文件第 ${index + 1} 行起]\n` : ""}${lines[index]}\n`;
    if (result.length + next.length > maxChars) {
      clipped = true;
      break;
    }
    result += next;
    previous = index;
  }
  return { content: result, truncated: clipped || wanted.size < lines.length };
}
