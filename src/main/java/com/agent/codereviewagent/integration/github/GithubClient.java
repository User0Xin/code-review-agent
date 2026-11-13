package com.agent.codereviewagent.integration.github;

import com.agent.codereviewagent.config.GithubProperties;
import com.agent.codereviewagent.service.DiscussionMissingException;
import com.agent.codereviewagent.service.DiffVersionChangedException;
import com.agent.codereviewagent.service.RepositoryClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 把固定 GitHub 仓库的 PR、文件和评论映射为审查服务的仓库接口。 */
public class GithubClient implements RepositoryClient {
    private static final int FILES_PER_PAGE = 100;
    private static final String RESOLVED_MARKER = "\n\n✅ 已解决\n<!-- code-review-agent:resolved -->";
    private final GithubApi api;
    private final GithubReviewThreads threads;

    public GithubClient(GithubProperties properties, ObjectMapper json) {
        api = new GithubApi(properties, json);
        threads = new GithubReviewThreads(api);
    }

    private String repository(long id) { return "/repos/" + GithubApi.path(api.repository(id)); }
    private String pull(long id, long number) { return repository(id) + "/pulls/" + number; }

    @Override
    public Optional<DiffRefs> diffRefs(long id, long number) {
        return refs(api.get(pull(id, number)));
    }

    private static Optional<DiffRefs> refs(JsonNode pr) {
        String head = pr.path("head").path("sha").asText("");
        String base = pr.path("base").path("sha").asText("");
        // GitHub 暴露 PR 两侧的 tip，diff 以这两侧提交的共同祖先为起点。
        return head.isBlank() || base.isBlank() ? Optional.empty() : Optional.of(new DiffRefs(head, base, null));
    }

    @Override
    public String readDiff(long id, long number, String head) {
        return readDiff(id, number, currentRefs(id, number, head));
    }

    private DiffRefs currentRefs(long id, long number, String head) {
        return diffRefs(id, number).filter(ref -> ref.headSha().equals(head))
                .orElseThrow(DiffVersionChangedException::new);
    }

    @Override
    public String readDiff(long id, long number, DiffRefs expected) {
        String prPath = pull(id, number);
        JsonNode pr = api.get(prPath);
        expected.requireCurrent(refs(pr));
        int count = pr.path("changed_files").asInt(-1);
        // GitHub 的 /pulls/{number}/files 最多返回 3000 个文件。
        if (count < 0 || count > 3000) { throw new IllegalStateException("GitHub PR 无法提供完整文件清单"); }
        ArrayNode diff = api.object().arrayNode();
        Set<String> paths = new HashSet<>();
        for (int offset = 0; offset < count; offset += FILES_PER_PAGE) {
            JsonNode page = api.get(prPath + "/files?per_page=100&page=" + (1 + offset / FILES_PER_PAGE));
            if (!page.isArray() || page.size() != Math.min(FILES_PER_PAGE, count - offset)) {
                throw new IllegalStateException("GitHub PR 文件清单不完整");
            }
            for (JsonNode file : page) {
                String filename = required(file, "filename");
                if (!paths.add(filename)) { throw new IllegalStateException("GitHub PR 文件清单不一致"); }
                String patch = file.path("patch").asText("");
                // 避免把被 GitHub 省略或截断的文本 patch 送给模型。二进制、纯重命名、
                // 权限变化等没有文本增删的文件仍保留状态和路径。
                long adds = patch.lines().filter(line -> line.startsWith("+")).count();
                long deletes = patch.lines().filter(line -> line.startsWith("-")).count();
                if (adds != file.path("additions").asLong(-1) || deletes != file.path("deletions").asLong(-1)) {
                    throw new IllegalStateException("GitHub PR 文件缺少完整的文本差异");
                }
                diff.addObject().put("new_path", filename).put("old_path", file.path("previous_filename").asText(filename))
                        .put("status", required(file, "status")).put("diff", patch);
            }
        }
        // GitHub 文件列表是可变 PR 的视图。核对分页前后 head 与 base，避免混入下一个版本。
        expected.requireCurrent(diffRefs(id, number));
        return diff.toString();
    }

    @Override
    public long createOverviewDiscussion(long id, long number, String body) {
        return key(api.send("POST", repository(id) + "/issues/" + number + "/comments",
                api.object().put("body", body)), true);
    }

    @Override
    public long createLineDiscussion(long id, long number, String path, Integer newLine, Integer oldLine,
                                     String head, String body) {
        return createLineDiscussion(id, number, path, newLine, oldLine, currentRefs(id, number, head), body);
    }

    @Override
    public long createLineDiscussion(long id, long number, String path, Integer newLine, Integer oldLine,
                                     DiffRefs expected, String body) {
        expected.requireCurrent(diffRefs(id, number));
        if (newLine == null && oldLine == null) { return createOverviewDiscussion(id, number, body); }
        int line = newLine == null ? oldLine : newLine;
        String side = newLine == null ? "LEFT" : "RIGHT";
        ObjectNode payload = api.object().put("body", body).put("commit_id", expected.headSha()).put("path", path)
                .put("line", line).put("side", side);
        try {
            return key(api.send("POST", pull(id, number) + "/comments", payload), false);
        } catch (GithubApi.RequestFailure error) {
            if (!error.rejectedLine()) { throw error; }
            JsonNode current = api.get(pull(id, number));
            expected.requireCurrent(refs(current));
            // 被拒的行不一定有可用锚点；锁定两端 SHA 的比较页也适用于删除行和改名文件。
            String repositoryUrl = required(current.path("base").path("repo"), "html_url");
            String comparison = repositoryUrl + "/compare/" + GithubApi.segment(expected.baseSha()) + "..."
                    + GithubApi.segment(expected.headSha());
            String safePath = path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String location = "\n\n原行评位置：<code>" + safePath + "</code>，" + side + " 第 " + line
                    + " 行 · [查看此次差异（" + expected.headSha() + "）](" + comparison + ")";
            return createOverviewDiscussion(id, number, body + location);
        }
    }

    /** PR 的 issue comment 和 review comment 各有独立编号；低位标出种类，重启后仍可还原。 */
    private static long key(JsonNode result, boolean overview) {
        long id = result.path("id").asLong();
        if (id <= 0) { throw new IllegalStateException("GitHub 未返回评论编号"); }
        return Math.multiplyExact(id, 2) + (overview ? 1 : 0);
    }

    private static boolean overview(long key) { return (key & 1) != 0; }
    private String comment(long id, long key) {
        return repository(id) + (overview(key) ? "/issues/comments/" : "/pulls/comments/") + (key / 2);
    }

    @Override
    public void deleteNote(long id, long number, long key) {
        try {
            api.send("DELETE", comment(id, key), null);
        } catch (GithubApi.RequestFailure failure) {
            if (!confirmedMissing(id, number, key, failure)) { throw failure; }
        }
    }

    /** 单项 404 也可能隐藏权限不足；只有能读完同一 PR 的同类评论且找不到它，才能按删除处理。 */
    private boolean confirmedMissing(long id, long number, long key, GithubApi.RequestFailure failure) {
        if (!failure.notFound()) { return false; }
        String collection = repository(id) + (overview(key) ? "/issues/" : "/pulls/") + number + "/comments";
        Set<Long> seen = new HashSet<>();
        for (int page = 1; ; page++) {
            JsonNode items = api.get(collection + "?per_page=100&page=" + page);
            if (!items.isArray() || items.size() > 100) { throw new IllegalStateException("GitHub 评论清单格式不正确"); }
            for (JsonNode item : items) {
                JsonNode itemId = item.path("id");
                if (!itemId.isIntegralNumber() || !itemId.canConvertToLong() || itemId.asLong() <= 0
                        || !seen.add(itemId.asLong())) {
                    throw new IllegalStateException("GitHub 评论分页清单不完整");
                }
                if (itemId.asLong() == key / 2) { return false; }
            }
            if (items.size() < 100) { return true; }
        }
    }

    private JsonNode readOverview(long id, long number, long key) {
        try {
            return api.get(comment(id, key));
        } catch (GithubApi.RequestFailure failure) {
            if (confirmedMissing(id, number, key, failure)) { throw new DiscussionMissingException(); }
            throw failure;
        }
    }

    @Override
    public List<String> listReplies(long id, long number, long key) {
        // 普通 PR 留言是平铺的；不能把其他 PR 留言当作这条发现的回复。
        return overview(key) ? List.of() : threads.replies(threads.find(id, number, key / 2));
    }

    @Override
    public boolean isDiscussionResolved(long id, long number, long key) {
        return overview(key) ? required(readOverview(id, number, key), "body").endsWith(RESOLVED_MARKER)
                : threads.find(id, number, key / 2).resolved();
    }

    @Override
    public void resolveDiscussion(long id, long number, long key) {
        resolveDiscussion(id, number, key, null);
    }

    @Override
    public void resolveDiscussion(long id, long number, long key, DiffRefs expected) {
        if (overview(key)) {
            // GitHub 的 PR 普通留言没有 resolved 字段。在本服务发出的原留言上保存状态。
            String path = comment(id, key);
            String body = required(readOverview(id, number, key), "body");
            if (!body.endsWith(RESOLVED_MARKER)) {
                if (expected != null) { expected.requireCurrent(diffRefs(id, number)); }
                try {
                    api.send("PATCH", path, api.object().put("body", body + RESOLVED_MARKER));
                } catch (GithubApi.RequestFailure failure) {
                    if (confirmedMissing(id, number, key, failure)) { throw new DiscussionMissingException(); }
                    throw failure;
                }
            }
        } else {
            var thread = threads.find(id, number, key / 2);
            // 定位讨论可能要翻多页；核验必须发生在查找之后、状态写入之前。
            if (expected != null) { expected.requireCurrent(diffRefs(id, number)); }
            threads.resolve(thread);
        }
    }

    @Override
    public List<String> listFiles(long id, String ref) {
        JsonNode tree = api.get(repository(id) + "/git/trees/" + GithubApi.segment(ref) + "?recursive=1");
        if (!tree.path("tree").isArray() || tree.path("truncated").asBoolean(true)) {
            throw new IllegalStateException("GitHub 仓库文件树不完整");
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode item : tree.get("tree")) {
            if ("blob".equals(item.path("type").asText())) { paths.add(required(item, "path")); }
        }
        return List.copyOf(paths);
    }

    @Override
    public String readFile(long id, String path, String ref) {
        return api.raw(repository(id) + "/contents/" + GithubApi.path(path) + "?ref=" + GithubApi.segment(ref));
    }

    @Override
    public String defaultBranch(long id) { return required(api.get(repository(id)), "default_branch"); }

    @Override
    public String branchHead(long id, String branch) {
        return required(api.get(repository(id) + "/commits/" + GithubApi.segment(branch)), "sha");
    }

    static String required(JsonNode object, String field) {
        String value = object.path(field).asText("");
        if (value.isBlank()) { throw new IllegalStateException("GitHub 响应缺少 " + field); }
        return value;
    }
}
