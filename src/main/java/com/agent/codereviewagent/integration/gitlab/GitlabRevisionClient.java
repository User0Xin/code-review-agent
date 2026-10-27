package com.agent.codereviewagent.integration.gitlab;

import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.service.RevisionClient;
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
import java.util.Map;
import java.util.Optional;

/** 提交已有规范文件并打开修订；使用持久化的源分支恢复中断的提交和合并请求创建。 */
@Component
public class GitlabRevisionClient implements RevisionClient {

    private final GitlabProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();

    public GitlabRevisionClient(GitlabProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Optional<RemoteRevision> findBySourceBranch(long projectId, String sourceBranch) {
        if (blank(sourceBranch)) { throw new IllegalArgumentException("编码规范修订缺少源分支"); }
        List<JsonNode> requests = list(project(projectId)
                + "/merge_requests?scope=all&state=all&source_branch=" + encode(sourceBranch));
        return requests.stream().filter(request -> sourceBranch.equals(request.path("source_branch").asText()))
                .findFirst().map(this::revision);
    }

    @Override
    public RemoteRevision createOrRecover(long projectId, String sourceBranch, String targetBranch,
                                          Map<String, String> files, String title, String description) {
        return createOrRecover(projectId, sourceBranch, targetBranch, targetBranch, files, title, description);
    }

    @Override
    public RemoteRevision createOrRecover(long projectId, String sourceBranch, String targetBranch, String baseRef,
                                          Map<String, String> files, String title, String description) {
        if (blank(targetBranch) || sourceBranch == null || sourceBranch.equals(targetBranch)) {
            throw new IllegalArgumentException("编码规范修订必须使用不同的源分支和规范分支");
        }
        if (blank(baseRef) || (!baseRef.equals(targetBranch) && !baseRef.matches("[0-9a-fA-F]{40}"))) {
            throw new IllegalArgumentException("编码规范修订缺少有效的规范基线提交");
        }
        Optional<RemoteRevision> existing = findBySourceBranch(projectId, sourceBranch);
        if (existing.isPresent()) { return existing.get(); }
        if (files == null || files.isEmpty() || files.entrySet().stream()
                .anyMatch(file -> blank(file.getKey()) || file.getValue() == null)) {
            throw new IllegalArgumentException("编码规范修订必须包含有效的已有文件内容");
        }
        if (blank(title) || blank(description)) {
            throw new IllegalArgumentException("编码规范修订缺少标题或说明");
        }
        String project = project(projectId);
        boolean branchExists = request("GET", project + "/repository/branches/" + encode(sourceBranch), null, true)
                .statusCode() != 404;
        boolean alreadyCommitted = branchExists && containsExpectedFiles(project, sourceBranch, files);
        if (!alreadyCommitted) {
            ObjectNode commit = json.createObjectNode().put("branch", sourceBranch)
                    .put("commit_message", "编码规范修订：" + title);
            if (baseRef.equals(targetBranch)) { commit.put("start_branch", targetBranch); }
            else { commit.put("start_sha", baseRef); }
            var actions = commit.putArray("actions");
            files.forEach((path, content) -> actions.addObject()
                    .put("action", "update").put("file_path", path).put("content", content));
            send("POST", project + "/repository/commits", commit);
        }
        ObjectNode mergeRequest = json.createObjectNode().put("source_branch", sourceBranch)
                .put("target_branch", targetBranch).put("title", title).put("description", description);
        return revision(send("POST", project + "/merge_requests", mergeRequest));
    }

    @Override
    public RemoteRevision get(long projectId, long iid) {
        if (iid <= 0) { throw new IllegalArgumentException("编码规范修订合并请求编号不正确"); }
        return revision(send("GET", project(projectId) + "/merge_requests/" + iid, null));
    }

    private boolean containsExpectedFiles(String project, String sourceBranch, Map<String, String> files) {
        boolean matches = true;
        for (Map.Entry<String, String> file : files.entrySet()) {
            String actual = request("GET", project + "/repository/files/" + encode(file.getKey())
                    + "/raw?ref=" + encode(sourceBranch), null, false).body();
            matches &= actual.equals(file.getValue());
        }
        return matches;
    }

    private RemoteRevision revision(JsonNode request) {
        long iid = request.path("iid").asLong();
        String state = request.path("state").asText();
        if (iid <= 0 || !List.of("opened", "closed", "merged", "locked").contains(state)) {
            throw new IllegalStateException("GitLab 修订合并请求响应缺少有效编号或状态");
        }
        return new RemoteRevision(iid, state);
    }

    private List<JsonNode> list(String path) {
        List<JsonNode> items = new ArrayList<>();
        int page = 1;
        while (true) {
            HttpResponse<String> response = request("GET", path + (path.contains("?") ? "&" : "?")
                    + "per_page=100&page=" + page, null, false);
            JsonNode batch = parse(response.body());
            if (!batch.isArray()) { throw new IllegalStateException("GitLab 修订列表响应格式不正确"); }
            batch.forEach(items::add);
            Optional<String> nextHeader = response.headers().firstValue("X-Next-Page");
            if (nextHeader.isPresent()) {
                String next = nextHeader.get();
                if (next.isBlank()) { break; }
                try {
                    int nextPage = Integer.parseInt(next);
                    if (nextPage <= page) { throw new NumberFormatException(); }
                    page = nextPage;
                }
                catch (NumberFormatException exception) {
                    throw new IllegalStateException("GitLab 修订列表分页格式不正确");
                }
            }
            else if (batch.size() == 100) { page++; }
            else { break; }
        }
        return items;
    }

    private JsonNode send(String method, String path, ObjectNode body) {
        return parse(request(method, path, body, false).body());
    }

    private JsonNode parse(String body) {
        try {
            JsonNode result = json.readTree(body);
            if (result == null || result.isNull()) { throw new IllegalStateException("GitLab 修订响应为空"); }
            return result;
        }
        catch (IOException exception) { throw new IllegalStateException("GitLab 修订响应不是有效 JSON"); }
    }

    private HttpResponse<String> request(String method, String path, ObjectNode body, boolean allowNotFound) {
        if (blank(properties.getBaseUrl()) || blank(properties.getToken())) {
            throw new IllegalStateException("GitLab base-url 或 token 尚未配置");
        }
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        if (!base.endsWith("/api/v4")) { base += "/api/v4"; }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(properties.getRequestTimeout()).header("PRIVATE-TOKEN", properties.getToken())
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (allowNotFound && response.statusCode() == 404) { return response; }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitLab 修订接口请求失败，HTTP " + response.statusCode());
            }
            return response;
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitLab 修订请求被中断");
        }
        catch (IOException exception) { throw new IllegalStateException("GitLab 修订请求连接失败"); }
    }

    private String project(long projectId) {
        if (properties.getProjectId() <= 0 || properties.getProjectId() != projectId) {
            throw new IllegalStateException("修订项目与配置的被审查仓库不一致");
        }
        return "/projects/" + projectId;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
