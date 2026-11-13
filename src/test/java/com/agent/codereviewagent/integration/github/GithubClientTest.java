package com.agent.codereviewagent.integration.github;

import com.agent.codereviewagent.config.GithubProperties;
import com.agent.codereviewagent.service.RepositoryClient.DiffRefs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Queue<Expected> responses = new ConcurrentLinkedQueue<>();
    private final Queue<String> errors = new ConcurrentLinkedQueue<>();
    private final Queue<String> bodies = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private GithubClient github;
    private static final String REPO = "/repos/owner/agent";
    private static final String PR = REPO + "/pulls/4";
    private static final DiffRefs REFS = new DiffRefs("head", "base", null);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().toASCIIString();
            Expected expected = responses.poll();
            if (expected == null || !route.equals(expected.route())
                    || !"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                errors.add("unexpected: " + route + "; expected " + expected);
                exchange.sendResponseHeaders(500, -1);
            } else {
                byte[] request = exchange.getRequestBody().readAllBytes();
                if (request.length > 0) { bodies.add(new String(request, StandardCharsets.UTF_8)); }
                byte[] data = expected.response().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(expected.status(), data.length == 0 ? -1 : data.length);
                if (data.length > 0) { exchange.getResponseBody().write(data); }
            }
            exchange.close();
        });
        server.start();
        GithubProperties props = new GithubProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setRepositoryId(7); props.setRepository("owner/agent"); props.setToken("test-token");
        github = new GithubClient(props, json);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        assertThat(errors).isEmpty();
        assertThat(responses).isEmpty();
    }

    @Test
    void readsRenamesAcrossFilePagesAndChecksBothCommitTips() throws Exception {
        String metadata = pr("base", 101);
        get(PR, metadata);
        get(PR + "/files?per_page=100&page=1", files(100, 0));
        get(PR + "/files?per_page=100&page=2", files(1, 100));
        get(PR, metadata);
        JsonNode diff = json.readTree(github.readDiff(7, 4, REFS));
        assertThat(diff.size()).isEqualTo(101);
        assertThat(diff.get(100).get("old_path").asText()).isEqualTo("before100");
        assertThat(diff.get(100).get("new_path").asText()).isEqualTo("after100");

        get(PR, pr("base", 1));
        get(PR + "/files?per_page=100&page=1", files(1, 0));
        get(PR, pr("moved-base", 1));
        assertThatThrownBy(() -> github.readDiff(7, 4, REFS)).hasMessageContaining("提交已变化");
    }

    @Test
    void rejectsPartialChangesAndUnsupportedFileCounts() {
        get(PR, pr("base", 1));
        get(PR + "/files?per_page=100&page=1", """
                [{"filename":"a","status":"modified","additions":4,"deletions":0,"patch":"+only one line"}]
                """);
        assertThatThrownBy(() -> github.readDiff(7, 4, REFS)).hasMessageContaining("完整的文本差异");
        get(PR, pr("base", 3001));
        assertThatThrownBy(() -> github.readDiff(7, 4, REFS)).hasMessageContaining("完整文件清单");
    }

    @Test
    void readsRawContentAtRequestedRefAndRejectsIncompleteTreesAndForeignRepository() {
        get(REPO, "{\"default_branch\":\"main\"}");
        get(REPO + "/commits/feature%2Fbranch", "{\"sha\":\"fixed\"}");
        get(REPO + "/git/trees/fixed?recursive=1", """
                {"truncated":false,"tree":[{"type":"tree","path":"docs"},{"type":"blob","path":"docs/a b.md"}]}
                """);
        get(REPO + "/contents/docs/a%20b.md?ref=fixed", "真实规范");
        assertThat(github.defaultBranch(7)).isEqualTo("main");
        assertThat(github.branchHead(7, "feature/branch")).isEqualTo("fixed");
        assertThat(github.listFiles(7, "fixed")).containsExactly("docs/a b.md");
        assertThat(github.readFile(7, "docs/a b.md", "fixed")).isEqualTo("真实规范");
        get(REPO + "/git/trees/big?recursive=1", "{\"truncated\":true,\"tree\":[]}");
        assertThatThrownBy(() -> github.listFiles(7, "big")).hasMessageContaining("不完整");
        assertThatThrownBy(() -> github.defaultBranch(8)).hasMessageContaining("仓库编号");
    }

    @Test
    void postsToCorrectSideAndDeletesEachKindUsingItsOwnEndpoint() throws Exception {
        get(PR, pr("base", 1));
        expect("POST", PR + "/comments", 201, "{\"id\":50}");
        long line = github.createLineDiscussion(7, 4, "main.java", null, 7, REFS, "删除行");
        JsonNode request = json.readTree(bodies.remove());
        assertThat(request.path("line").asInt()).isEqualTo(7);
        assertThat(request.path("side").asText()).isEqualTo("LEFT");
        assertThat(request.path("commit_id").asText()).isEqualTo("head");
        expect("POST", REPO + "/issues/4/comments", 201, "{\"id\":50}");
        long overview = github.createOverviewDiscussion(7, 4, "概览");
        assertThat(line).isPositive().isNotEqualTo(overview);
        expect("DELETE", REPO + "/pulls/comments/50", 204, "");
        github.deleteNote(7, 4, line);
        expect("DELETE", REPO + "/issues/comments/50", 204, "");
        github.deleteNote(7, 4, overview);
    }

    @Test
    void onlyFallsBackToOverviewOnLineRejectionAtSameVersion() {
        get(PR, pr("base", 1));
        expect("POST", PR + "/comments", 422, "{\"message\":\"Line must be part of the diff\"}");
        get(PR, pr("base", 1));
        expect("POST", REPO + "/issues/4/comments", 201, "{\"id\":42}");
        assertThat(github.createLineDiscussion(7, 4, "a.txt", 2, null, REFS, "text")).isEqualTo(85);
        get(PR, pr("base", 1));
        expect("POST", PR + "/comments", 403, "{\"message\":\"secret error details\"}");
        assertThatThrownBy(() -> github.createLineDiscussion(7, 4, "a.txt", 2, null, REFS, "text"))
                .hasMessageContaining("403").hasMessageNotContaining("secret");
    }

    @Test
    void marksOnlyTheSpecificOverviewAsResolvedWithoutCollectingUnrelatedComments() throws Exception {
        String overviewPath = REPO + "/issues/comments/42";
        long key = 85;
        assertThat(github.listReplies(7, 4, key)).isEmpty();
        get(overviewPath, "{\"body\":\"问题\"}");
        assertThat(github.isDiscussionResolved(7, 4, key)).isFalse();
        get(overviewPath, "{\"body\":\"问题\"}");
        expect("PATCH", overviewPath, 200, "{\"id\":42}");
        github.resolveDiscussion(7, 4, key);
        String updated = bodies.remove();
        assertThat(json.readTree(updated).path("body").asText()).startsWith("问题").contains("已解决");
        get(overviewPath, updated);
        assertThat(github.isDiscussionResolved(7, 4, key)).isTrue();
        get(overviewPath, updated);
        github.resolveDiscussion(7, 4, key);
    }

    @Test
    void findsThreadBy64BitRootIdFiltersOwnRepliesAndResolvesNativeThread() throws Exception {
        long remoteId = 3_000_000_001L;
        long key = 2 * remoteId;
        String first = connection("[]", true);
        String last = connection("""
                [{"id":"thread","isResolved":false,"comments":{"nodes":[{"fullDatabaseId":"3000000001"}]}}]
                """, false);
        threads(first); threads(last);
        graph("{\"node\":{\"comments\":" + connection("""
                [{"fullDatabaseId":"3000000001","viewerDidAuthor":false,"body":"root"},
                 {"fullDatabaseId":"3000000002","viewerDidAuthor":true,"body":"own"}]
                """, true) + "}}");
        graph("{\"node\":{\"comments\":" + connection("""
                [{"fullDatabaseId":"3000000003","viewerDidAuthor":false,"body":"回复"}]
                """, false) + "}}");
        assertThat(github.listReplies(7, 4, key)).containsExactly("回复");
        assertThat(json.readTree(bodies.remove()).at("/variables/owner").asText()).isEqualTo("owner");
        assertThat(json.readTree(bodies.remove()).at("/variables/after").asText()).isEqualTo("next");
        bodies.clear();
        threads(last);
        graph("{\"resolveReviewThread\":{\"thread\":{\"isResolved\":true}}}");
        github.resolveDiscussion(7, 4, key);
        bodies.remove();
        assertThat(json.readTree(bodies.remove()).at("/variables/id").asText()).isEqualTo("thread");
    }

    private void threads(String connection) { graph("{\"repository\":{\"pullRequest\":{\"reviewThreads\":" + connection + "}}}"); }
    private void graph(String data) { expect("POST", "/graphql", 200, "{\"data\":" + data + "}"); }
    private static String connection(String nodes, boolean next) {
        return "{\"nodes\":" + nodes + ",\"pageInfo\":{\"hasNextPage\":" + next + ",\"endCursor\":\"next\"}}";
    }
    private String files(int count, int start) {
        ArrayNode files = json.createArrayNode();
        for (int index = start; index < start + count; index++) {
            files.addObject().put("filename", "after" + index).put("previous_filename", "before" + index)
                    .put("status", "renamed").put("additions", 1).put("deletions", 1)
                    .put("patch", "@@ -1 +1 @@\n-a\n+b");
        }
        return files.toString();
    }
    private static String pr(String base, int count) {
        return "{\"head\":{\"sha\":\"head\"},\"base\":{\"sha\":\"" + base + "\"},\"changed_files\":" + count + "}";
    }
    private void get(String path, String body) { expect("GET", path, 200, body); }
    private void expect(String method, String path, int status, String body) {
        responses.add(new Expected(method + " " + path, status, body));
    }
    private record Expected(String route, int status, String response) { }
}
