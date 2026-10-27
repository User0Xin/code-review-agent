package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.RevisionSummarizer;
import com.agent.codereviewagent.service.SpecDocumentPicker;
import com.agent.codereviewagent.service.WorkItemClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 webhook 经过真实修订适配器访问本机 HTTP 服务，验证创建与中断恢复。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/gitlab-revision-transport-test.db",
        "review.revision.new-findings-threshold=1",
        "review.revision.minimum-group-size=1",
        "review.standards.branch=dev"
})
@AutoConfigureMockMvc
@Import(GitlabRevisionTransportWebhookTest.Fakes.class)
class GitlabRevisionTransportWebhookTest {

    private static final String PATH = "docs/coding.md";
    private static final String BASE_SHA = "a".repeat(40);
    private static final String ORIGINAL = "# 编码规范\n\n资源应由调用者管理。\n";
    private static final String UPDATED = ORIGINAL.replace("资源应由调用者管理。", "资源应由调用者管理，并及时关闭。");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GitlabClient GITLAB = mock(GitlabClient.class);
    private static final AtomicLong NOTES = new AtomicLong();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final Map<String, String> FILES = new ConcurrentHashMap<>();
    private static final HttpServer SERVER = startServer();
    private static volatile List<Reviewer.CommentDraft> drafts = List.of();
    private static volatile String sourceBranch;
    private static volatile boolean branchExists;
    private static volatile boolean remoteExists;
    private static volatile boolean failNextMrResponse;
    private static volatile boolean createBeforeFailure;
    private static volatile boolean paginateExisting;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("review.gitlab.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("review.gitlab.token", () -> "revision-test-token");
        registry.add("review.gitlab.project-id", () -> 9);
    }

    @BeforeEach
    void prepare() {
        reset(GITLAB);
        when(GITLAB.diffRefs(9, 3)).thenReturn(Optional.of(new GitlabClient.DiffRefs("head")));
        when(GITLAB.branchHead(9, "dev")).thenReturn(BASE_SHA);
        when(GITLAB.listFiles(9, BASE_SHA)).thenReturn(List.of(PATH));
        when(GITLAB.listFiles(9, "head")).thenReturn(List.of());
        when(GITLAB.readFile(9, PATH, BASE_SHA)).thenReturn(ORIGINAL);
        when(GITLAB.readDiff(9, 3, "head")).thenReturn("diff");
        when(GITLAB.listReplies(anyLong(), anyLong(), anyLong())).thenReturn(List.of());
        when(GITLAB.createOverviewDiscussion(anyLong(), anyLong(), anyString()))
                .thenAnswer(invocation -> NOTES.incrementAndGet());
        NOTES.set(0);
        drafts = List.of(new Reviewer.CommentDraft("main.go", null, null, "缺陷", "资源关闭", "响应体没有关闭。"));
        RECEIVED.clear();
        FILES.clear();
        sourceBranch = "";
        branchExists = false;
        remoteExists = false;
        failNextMrResponse = false;
        createBeforeFailure = false;
        paginateExisting = false;
        jdbc.update("DELETE FROM revision_checkpoint");
        jdbc.update("DELETE FROM coding_standard_revision");
        jdbc.update("DELETE FROM review_failure_note");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM finding");
    }

    @AfterAll
    static void stop() { SERVER.stop(0); }

    @Test
    void webhookCommitsOnlyUpdatesFromThePinnedStandardCommitAndTargetsTheStandardBranch() throws Exception {
        webhook();

        assertThat(RECEIVED).filteredOn(received -> received.path().endsWith("/repository/commits"))
                .singleElement().satisfies(received -> {
                    assertThat(received.method()).isEqualTo("POST");
                    assertThat(received.body().path("start_sha").asText()).isEqualTo(BASE_SHA);
                    assertThat(received.body().has("start_branch")).isFalse();
                    assertThat(received.body().path("branch").asText()).isEqualTo(sourceBranch).isNotEqualTo("dev");
                    assertThat(received.body().path("actions")).hasSize(1);
                    assertThat(received.body().path("actions").get(0).path("action").asText()).isEqualTo("update");
                    assertThat(received.body().path("actions").get(0).path("file_path").asText()).isEqualTo(PATH);
                    assertThat(received.body().path("actions").get(0).path("content").asText()).isEqualTo(UPDATED);
                    assertThat(received.body().has("force")).isFalse();
                });
        assertThat(mrPosts()).singleElement().satisfies(received -> {
            assertThat(received.body().path("source_branch").asText()).isEqualTo(sourceBranch);
            assertThat(received.body().path("target_branch").asText()).isEqualTo("dev");
            assertThat(received.body().path("title").asText()).contains("编码规范修订");
            assertThat(received.body().path("description").asText()).contains(PATH, "资源关闭", "依据的发现");
        });
        assertCompleted();
        assertThat(RECEIVED).allSatisfy(received -> assertThat(received.token()).isEqualTo("revision-test-token"));
        assertThat(RECEIVED).noneMatch(received -> received.path().endsWith("/merge") || received.path().endsWith("/approve"));
    }

    @Test
    void aFailedMrCreationRetriesTheSavedBranchWithoutRepeatingItsCommit() throws Exception {
        failNextMrResponse = true;
        webhook();
        String savedBranch = jdbc.queryForObject("SELECT source_branch FROM coding_standard_revision", String.class);
        assertThat(jdbc.queryForObject("SELECT state FROM coding_standard_revision", String.class)).isEqualTo("PREPARING");
        assertThat(jdbc.queryForObject("SELECT revision_id FROM finding", Long.class)).isNull();
        drafts = List.of();

        webhook();

        assertThat(sourceBranch).isEqualTo(savedBranch);
        assertThat(RECEIVED).filteredOn(received -> received.path().endsWith("/repository/commits")).hasSize(1);
        assertThat(RECEIVED).anySatisfy(received -> {
            assertThat(received.path()).endsWith("/repository/files/" + PATH + "/raw");
            assertThat(query(received.query(), "ref")).isEqualTo(savedBranch);
        });
        assertThat(mrPosts()).hasSize(2).allSatisfy(received ->
                assertThat(received.body().path("source_branch").asText()).isEqualTo(savedBranch));
        verify(GITLAB, times(1)).createOverviewDiscussion(anyLong(), anyLong(), anyString());
        assertCompleted();
    }

    @Test
    void anExistingRemoteRevisionIsRecoveredFromTheNextPageWithoutAnotherCommitOrMr() throws Exception {
        failNextMrResponse = true;
        createBeforeFailure = true;
        paginateExisting = true;
        webhook();
        drafts = List.of();

        webhook();

        assertThat(RECEIVED).filteredOn(received -> received.path().endsWith("/repository/commits")).hasSize(1);
        assertThat(mrPosts()).hasSize(1);
        assertThat(RECEIVED).anySatisfy(received -> {
            assertThat(received.method()).isEqualTo("GET");
            assertThat(received.path()).endsWith("/merge_requests");
            assertThat(query(received.query(), "state")).isEqualTo("all");
            assertThat(query(received.query(), "source_branch")).isEqualTo(sourceBranch);
            assertThat(query(received.query(), "page")).isEqualTo("2");
        });
        assertCompleted();
    }

    private void assertCompleted() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coding_standard_revision", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM coding_standard_revision", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT merge_request_iid FROM coding_standard_revision", Long.class)).isEqualTo(77L);
        assertThat(jdbc.queryForObject("SELECT base_ref FROM coding_standard_revision", String.class)).isEqualTo(BASE_SHA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    private List<Received> mrPosts() {
        return RECEIVED.stream().filter(received -> received.method().equals("POST")
                && received.path().endsWith("/merge_requests")).toList();
    }

    private void webhook() throws Exception {
        mvc.perform(post("/gitlab/webhooks/merge-requests").header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"object_kind":"merge_request","project":{"id":9},"object_attributes":
                                  {"iid":3,"action":"update","oldrev":"old","source_branch":"main"}}
                                """))
                .andExpect(status().isOk());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v4", GitlabRevisionTransportWebhookTest::respond);
            server.start();
            return server;
        }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getRawQuery();
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
        RECEIVED.add(new Received(method, path, query, exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"), body));
        String response;
        int status = 200;
        if (path.endsWith("/merge_requests") && method.equals("GET")) {
            if (remoteExists && paginateExisting && "1".equals(query(query, "page"))) {
                exchange.getResponseHeaders().set("X-Next-Page", "2");
                response = "[]";
            }
            else { response = remoteExists ? "[" + remote() + "]" : "[]"; }
        }
        else if (path.contains("/repository/branches/")) {
            status = branchExists ? 200 : 404;
            response = branchExists ? "{\"name\":" + JSON.writeValueAsString(sourceBranch) + "}" : "{}";
        }
        else if (path.endsWith("/repository/commits") && method.equals("POST")) {
            branchExists = true;
            sourceBranch = body.path("branch").asText();
            for (JsonNode action : body.path("actions")) { FILES.put(action.path("file_path").asText(), action.path("content").asText()); }
            status = 201;
            response = "{\"id\":\"new-commit\"}";
        }
        else if (path.endsWith("/raw")) { response = FILES.getOrDefault(PATH, ""); }
        else if (path.endsWith("/merge_requests") && method.equals("POST")) {
            if (failNextMrResponse) {
                failNextMrResponse = false;
                remoteExists = createBeforeFailure;
                status = 503;
                response = "{\"message\":\"response interrupted\"}";
            }
            else {
                remoteExists = true;
                status = 201;
                response = remote();
            }
        }
        else if (path.endsWith("/merge_requests/77")) { response = remote(); }
        else { status = 404; response = "{}"; }
        byte[] output = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, output.length);
        exchange.getResponseBody().write(output);
        exchange.close();
    }

    private static String remote() throws IOException {
        return "{\"iid\":77,\"state\":\"opened\",\"source_branch\":" + JSON.writeValueAsString(sourceBranch) + "}";
    }

    private static String query(String query, String name) {
        if (query == null) { return ""; }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) { return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""; }
        }
        return "";
    }

    record Received(String method, String path, String query, String token, JsonNode body) { }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary GitlabClient revisionTransportGitlab() { return GITLAB; }
        @Bean @Primary Reviewer revisionTransportReviewer() { return request -> Reviewer.ReviewFindings.of(drafts); }
        @Bean @Primary WorkItemClient revisionTransportWorkItems() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker revisionTransportSpecPicker() { return paths -> List.of(); }
        @Bean @Primary CodingStandardPicker revisionTransportStandards() { return paths -> List.of(PATH); }
        @Bean @Primary RevisionSummarizer revisionTransportSummary() {
            return (findings, standards) -> new RevisionSummarizer.Summary(List.of(new RevisionSummarizer.Replacement(
                    "资源关闭", PATH, "资源应由调用者管理。", "资源应由调用者管理，并及时关闭。",
                    findings.stream().map(FindingEntity::getId).toList(), "多次发现资源未关闭，应明确关闭要求。")));
        }
    }
}
