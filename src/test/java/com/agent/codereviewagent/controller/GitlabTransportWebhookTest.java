package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.Reviewer;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 仍从 webhook 进入，GitLab 仅以本机 HTTP 服务替身替换，验证真实 REST 传输。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GitlabTransportWebhookTest.Fakes.class)
class GitlabTransportWebhookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final List<JsonNode> POSTED = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = startServer();
    private static volatile boolean failSecond;
    private static volatile String reply = "";
    private static volatile boolean discussionOnSecondPage;
    private static volatile List<Reviewer.CommentDraft> drafts = List.of();
    private static volatile List<Long> resolved = List.of();

    @Autowired private MockMvc mvc;
    @Autowired private FindingService findings;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("review.gitlab.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("review.gitlab.token", () -> "test-token");
        registry.add("review.gitlab.project-id", () -> 9);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:target/gitlab-transport-test.db");
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
        POSTED.clear();
        failSecond = false;
        reply = "";
        discussionOnSecondPage = false;
        drafts = List.of();
        resolved = List.of();
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM review_failure_note");
    }

    @AfterAll
    static void stop() { SERVER.stop(0); }

    @Test
    void webhookWritesChineseCommentsWithFullDiffPositionAndStoresNoteIds() throws Exception {
        drafts = List.of(
                new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", "错误检查", "错误没有处理。"),
                new Reviewer.CommentDraft(null, null, null, "编码规范", "命名", "命名无法说明用途。"));

        webhook("open");

        assertThat(POSTED).hasSize(2);
        JsonNode position = POSTED.get(0).path("position");
        assertThat(position.path("base_sha").asText()).isEqualTo("base");
        assertThat(position.path("start_sha").asText()).isEqualTo("start");
        assertThat(position.path("head_sha").asText()).isEqualTo("head");
        assertThat(position.path("old_path").asText()).isEqualTo("old.go");
        assertThat(position.path("new_path").asText()).isEqualTo("renamed.go");
        assertThat(position.path("new_line").asInt()).isEqualTo(12);
        assertThat(POSTED.get(1).has("position")).isFalse();
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(2)
                .extracting(finding -> finding.getDiscussionId()).containsExactly(101L, 102L);
        assertThat(RECEIVED).allSatisfy(received -> assertThat(received.token()).isEqualTo("test-token"));
    }

    @Test
    void laterWebhookMapsStoredNoteIdToStringDiscussionIdBeforeResolving() throws Exception {
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", null, "错误没有处理。"));
        webhook("open");
        drafts = List.of();
        resolved = List.of(101L);
        reply = "谢谢，已修改。";
        discussionOnSecondPage = true;

        webhook("update");

        assertThat(RECEIVED).anySatisfy(received -> {
            assertThat(received.method()).isEqualTo("PUT");
            assertThat(received.path()).isEqualTo("/api/v4/projects/9/merge_requests/3/discussions/thread-101");
            assertThat(received.body().path("resolved").asBoolean()).isTrue();
        });
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
    }

    @Test
    void failedHttpWriteRetainsTheEarlierFindingAndWritesOnlyOneFailureDiscussion() throws Exception {
        failSecond = true;
        drafts = List.of(
                new Reviewer.CommentDraft(null, null, null, "缺陷", null, "第一个问题。"),
                new Reviewer.CommentDraft(null, null, null, "缺陷", null, "第二个问题。"));

        webhook("open");

        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
        assertThat(POSTED).hasSize(3);
        assertThat(POSTED.get(2).path("body").asText()).contains("有 1 条没能写回");
        assertThat(RECEIVED).noneSatisfy(received -> assertThat(received.method()).isEqualTo("DELETE"));
    }

    private void webhook(String action) throws Exception {
        String body = """
                {"object_kind":"merge_request","project":{"id":9},"object_attributes":
                  {"iid":3,"action":"%s","oldrev":"previous","source_branch":"main"}}
                """.formatted(action);
        mvc.perform(post("/gitlab/webhooks/merge-requests").header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v4", GitlabTransportWebhookTest::respond);
            server.start();
            return server;
        }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
        RECEIVED.add(new Received(method, path, exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"), body));
        String response;
        int status = 200;
        if (path.endsWith("/merge_requests/3")) {
            response = "{\"diff_refs\":{\"head_sha\":\"head\",\"base_sha\":\"base\",\"start_sha\":\"start\"}}";
        }
        else if (path.endsWith("/diffs")) {
            response = "[{\"old_path\":\"old.go\",\"new_path\":\"renamed.go\",\"diff\":\"@@ -1 +1 @@\\n-old\\n+new\"}]";
        }
        else if (path.endsWith("/repository/tree")) { response = "[]"; }
        else if (path.endsWith("/projects/9")) { response = "{\"default_branch\":\"main\"}"; }
        else if (path.endsWith("/discussions") && method.equals("POST")) {
            POSTED.add(body);
            int number = POSTED.size();
            if (failSecond && number == 2) {
                status = 503;
                response = "{\"message\":\"unavailable\"}";
            }
            else {
                status = 201;
                response = "{\"id\":\"thread-" + (100 + number) + "\",\"notes\":[{\"id\":" + (100 + number) + "}]}";
            }
        }
        else if (path.endsWith("/discussions") && method.equals("GET")) {
            if (discussionOnSecondPage && exchange.getRequestURI().getQuery().contains("page=1")) {
                exchange.getResponseHeaders().set("X-Next-Page", "2");
                response = "[]";
            }
            else {
                response = "[{\"id\":\"thread-101\",\"notes\":[{\"id\":101,\"body\":\"发现\"},"
                        + "{\"id\":201,\"body\":" + JSON.writeValueAsString(reply) + "}]}]";
            }
        }
        else if (path.endsWith("/discussions/thread-101") && method.equals("PUT")) { response = "{}"; }
        else { status = 404; response = "{}"; }
        byte[] output = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, output.length);
        exchange.getResponseBody().write(output);
        exchange.close();
    }

    record Received(String method, String path, String token, JsonNode body) { }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary Reviewer transportReviewer() {
            return request -> new Reviewer.ReviewFindings(drafts, resolved);
        }
        @Bean @Primary WorkItemClient transportWorkItems() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker transportSpecPicker() { return paths -> List.of(); }
        @Bean @Primary CodingStandardPicker transportStandardsPicker() { return paths -> List.of(); }
    }
}
