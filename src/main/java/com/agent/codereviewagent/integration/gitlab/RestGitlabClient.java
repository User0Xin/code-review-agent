package com.agent.codereviewagent.integration.gitlab;

import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.service.GitlabClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** GitLab REST v4 适配器。库内 discussionId 保存首条 note 的数字 ID。 */
@Component
public class RestGitlabClient implements GitlabClient {

    private final GitlabProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();

    public RestGitlabClient(GitlabProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
        JsonNode refs = get(mergeRequest(projectId, mergeRequestIid)).path("diff_refs");
        if (refs.path("head_sha").asText().isBlank()
                || refs.path("base_sha").asText().isBlank()
                || refs.path("start_sha").asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DiffRefs(refs.path("head_sha").asText()));
    }

    /** 以不可变 diff version 固定本次提交，拒绝 GitLab 因大小限制裁掉的差异。 */
    @Override
    public String readDiff(long projectId, long mergeRequestIid, String headSha) {
        String mergeRequest = mergeRequest(projectId, mergeRequestIid);
        for (JsonNode version : list(mergeRequest + "/versions")) {
            if (!headSha.equals(version.path("head_commit_sha").asText())) { continue; }
            JsonNode detail = get(mergeRequest + "/versions/" + version.path("id").asLong());
            JsonNode diffs = detail.path("diffs");
            String size = detail.path("real_size").asText();
            if (!headSha.equals(detail.path("head_commit_sha").asText())
                    || !"collected".equals(detail.path("state").asText())
                    || !diffs.isArray() || !size.matches("[0-9]+")
                    || !size.equals(Integer.toString(diffs.size()))) {
                throw new IllegalStateException("GitLab diff 版本尚未完整收集或已被截断");
            }
            for (JsonNode diff : diffs) {
                if (diff.path("collapsed").asBoolean() || diff.path("too_large").asBoolean()) {
                    throw new IllegalStateException("GitLab diff 文件被折叠或超出大小限制");
                }
            }
            return diffs.toString();
        }
        throw new IllegalStateException("找不到这次审查提交对应的 GitLab diff 版本");
    }

    @Override
    public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) {
        return createdNoteId(send("POST", mergeRequest(projectId, mergeRequestIid) + "/discussions",
                json.createObjectNode().put("body", body)));
    }

    @Override
    public long createLineDiscussion(long projectId, long mergeRequestIid, String path,
                                     Integer newLine, Integer oldLine, String headSha, String body) {
        String mergeRequest = mergeRequest(projectId, mergeRequestIid);
        JsonNode refs = get(mergeRequest).path("diff_refs");
        if (!headSha.equals(refs.path("head_sha").asText())) {
            throw new IllegalStateException("合并请求提交已变化，不能写入旧审查的行评论");
        }
        String oldPath = path;
        String newPath = path;
        for (JsonNode diff : list(mergeRequest + "/diffs")) {
            if (path.equals(diff.path("new_path").asText()) || path.equals(diff.path("old_path").asText())) {
                oldPath = diff.path("old_path").asText();
                newPath = diff.path("new_path").asText();
                break;
            }
        }
        ObjectNode payload = json.createObjectNode().put("body", body);
        ObjectNode position = payload.putObject("position");
        position.put("position_type", "text");
        position.put("base_sha", refs.path("base_sha").asText());
        position.put("start_sha", refs.path("start_sha").asText());
        position.put("head_sha", headSha);
        position.put("old_path", oldPath);
        position.put("new_path", newPath);
        if (newLine != null) { position.put("new_line", newLine); }
        if (oldLine != null) { position.put("old_line", oldLine); }
        return createdNoteId(send("POST", mergeRequest + "/discussions", payload));
    }

    @Override
    public void deleteNote(long projectId, long mergeRequestIid, long noteId) {
        send("DELETE", mergeRequest(projectId, mergeRequestIid) + "/notes/" + noteId, null);
    }

    @Override
    public List<String> listReplies(long projectId, long mergeRequestIid, long discussionId) {
        JsonNode discussion = discussion(projectId, mergeRequestIid, discussionId);
        List<String> replies = new ArrayList<>();
        for (JsonNode note : discussion.path("notes")) {
            if (note.path("id").asLong() != discussionId && !note.path("system").asBoolean()) {
                replies.add(note.path("body").asText());
            }
        }
        return List.copyOf(replies);
    }

    @Override
    public boolean isDiscussionResolved(long projectId, long mergeRequestIid, long discussionId) {
        return discussion(projectId, mergeRequestIid, discussionId)
                .path("notes").path(0).path("resolved").asBoolean();
    }

    @Override
    public void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId) {
        JsonNode discussion = discussion(projectId, mergeRequestIid, discussionId);
        send("PUT", mergeRequest(projectId, mergeRequestIid) + "/discussions/"
                + encode(discussion.path("id").asText()), json.createObjectNode().put("resolved", true));
    }

    @Override
    public List<String> listFiles(long projectId, String ref) {
        List<String> files = new ArrayList<>();
        for (JsonNode entry : list(project(projectId) + "/repository/tree?recursive=true&ref=" + encode(ref))) {
            if ("blob".equals(entry.path("type").asText())) { files.add(entry.path("path").asText()); }
        }
        return List.copyOf(files);
    }

    @Override
    public String readFile(long projectId, String path, String ref) {
        return request("GET", project(projectId) + "/repository/files/" + encode(path)
                + "/raw?ref=" + encode(ref), null).body();
    }

    @Override
    public String defaultBranch(long projectId) {
        String branch = get(project(projectId)).path("default_branch").asText();
        if (branch.isBlank()) { throw new IllegalStateException("被审查仓库没有默认分支"); }
        return branch;
    }

    @Override
    public String branchHead(long projectId, String branch) {
        String sha = get(project(projectId) + "/repository/branches/" + encode(branch)).path("commit").path("id").asText();
        if (sha.isBlank()) { throw new IllegalStateException("规范分支缺少提交标识"); }
        return sha;
    }

    private JsonNode discussion(long projectId, long mergeRequestIid, long noteId) {
        for (JsonNode discussion : list(mergeRequest(projectId, mergeRequestIid) + "/discussions")) {
            for (JsonNode note : discussion.path("notes")) {
                if (note.path("id").asLong() == noteId) { return discussion; }
            }
        }
        throw new IllegalStateException("找不到发现对应的 GitLab 讨论");
    }

    private long createdNoteId(JsonNode discussion) {
        long noteId = discussion.path("notes").path(0).path("id").asLong();
        if (noteId <= 0) { throw new IllegalStateException("GitLab 未返回已创建评论的编号"); }
        return noteId;
    }

    private String project(long projectId) {
        if (properties.getProjectId() <= 0 || properties.getProjectId() != projectId) {
            throw new IllegalStateException("webhook 项目与配置的被审查仓库不一致");
        }
        return "/projects/" + projectId;
    }

    private String mergeRequest(long projectId, long mergeRequestIid) {
        return project(projectId) + "/merge_requests/" + mergeRequestIid;
    }

    private List<JsonNode> list(String path) {
        List<JsonNode> items = new ArrayList<>();
        String page = "1";
        do {
            HttpResponse<String> response = request("GET", path + (path.contains("?") ? "&" : "?")
                    + "per_page=100&page=" + page, null);
            JsonNode batch = parse(response.body());
            if (!batch.isArray()) { throw new IllegalStateException("GitLab 列表响应格式不正确"); }
            batch.forEach(items::add);
            page = response.headers().firstValue("X-Next-Page").orElse("");
            if (!page.isBlank() && !page.matches("[1-9][0-9]*")) {
                throw new IllegalStateException("GitLab 分页响应格式不正确");
            }
        } while (!page.isBlank());
        return items;
    }

    private JsonNode get(String path) { return send("GET", path, null); }

    private JsonNode send(String method, String path, ObjectNode payload) {
        String body = request(method, path, payload).body();
        return body.isBlank() ? json.nullNode() : parse(body);
    }

    private JsonNode parse(String body) {
        try { return json.readTree(body); }
        catch (IOException exception) { throw new IllegalStateException("GitLab 响应不是有效 JSON", exception); }
    }

    private HttpResponse<String> request(String method, String path, ObjectNode payload) {
        if (properties.getBaseUrl().isBlank() || properties.getToken().isBlank()) {
            throw new IllegalStateException("GitLab base-url 或 token 尚未配置");
        }
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        if (!base.endsWith("/api/v4")) { base += "/api/v4"; }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(properties.getRequestTimeout())
                .header("PRIVATE-TOKEN", properties.getToken())
                .header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitLab 接口请求失败，HTTP " + response.statusCode());
            }
            return response;
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitLab 请求被中断", exception);
        }
        catch (IOException exception) { throw new IllegalStateException("GitLab 连接失败", exception); }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
