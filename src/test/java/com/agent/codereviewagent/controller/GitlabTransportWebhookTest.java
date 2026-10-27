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
    private static volatile boolean missingPaginationHeader;
    private static volatile int rejectPositionStatus;
    private static volatile boolean emptyDiffVersion;
    private static volatile boolean changeBaseAfterModel;
    private static volatile boolean includeOtherVersionFirst;
    private static volatile String currentBase = "base";
    private static volatile boolean firstDiscussionResolved;
    private static volatile boolean includeOwnAndSystemReplies;
    private static volatile Reviewer.ReviewRequest lastReview;
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
        missingPaginationHeader = false;
        rejectPositionStatus = 0;
        emptyDiffVersion = false;
        changeBaseAfterModel = false;
        includeOtherVersionFirst = false;
        currentBase = "base";
        firstDiscussionResolved = false;
        includeOwnAndSystemReplies = false;
        lastReview = null;
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
    void aProblemReturningAfterAnEarlierWebhookResolvedItOpensANewDiscussion() throws Exception {
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", null, "错误没有处理。"));
        webhook("open");
        drafts = List.of();
        resolved = List.of(101L);
        discussionOnSecondPage = true;

        webhook("update");

        assertThat(firstDiscussionResolved).isTrue();
        assertThat(POSTED).hasSize(1);
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 30, null, "缺陷", null, "错误没有处理。"));
        resolved = List.of();

        webhook("update");

        assertThat(POSTED).hasSize(2);
        assertThat(POSTED).extracting(posted -> posted.path("body").asText())
                .containsExactly("错误没有处理。", "错误没有处理。");
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(2)
                .extracting(finding -> finding.getDiscussionId()).containsExactly(101L, 102L);
        assertThat(RECEIVED).filteredOn(received -> received.method().equals("PUT"))
                .singleElement().satisfies(received ->
                        assertThat(received.path()).endsWith("/discussions/thread-101"));
        assertThat(RECEIVED).noneSatisfy(received -> assertThat(received.method()).isEqualTo("DELETE"));
    }

    @Test
    void laterReviewReceivesAllDeveloperRepliesInOrderAndExcludesTheServiceAccountAndSystemNotes() throws Exception {
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", null, "错误没有处理。"));
        webhook("open");
        drafts = List.of();
        reply = "最初认为不需要修改。";
        includeOwnAndSystemReplies = true;
        discussionOnSecondPage = true;

        webhook("update");

        assertThat(lastReview.priorFindings()).singleElement().satisfies(finding ->
                assertThat(finding.replies()).containsExactly("最初认为不需要修改。", "复核后确认需要修改。", "补充调用链供参考。"));
        assertThat(POSTED).hasSize(1);
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

    @Test
    void aRejectedDiffPositionFallsBackToOneOverviewDiscussion() throws Exception {
        rejectPositionStatus = 422;
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", null, "错误没有处理。"));

        webhook("open");

        assertThat(POSTED).hasSize(2);
        assertThat(POSTED.get(0).has("position")).isTrue();
        assertThat(POSTED.get(1).has("position")).isFalse();
        assertThat(POSTED.get(1).path("body").asText()).isEqualTo("错误没有处理。");
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding ->
                assertThat(finding.getDiscussionId()).isEqualTo(102L));
    }

    @Test
    void aServerFailureDoesNotPublishTheFindingAgainAsAnOverview() throws Exception {
        rejectPositionStatus = 503;
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", null, "错误没有处理。"));

        webhook("open");

        assertThat(POSTED).hasSize(2);
        assertThat(POSTED.get(1).path("body").asText()).startsWith("审查失败：有 1 条没能写回");
        assertThat(findings.findByMergeRequest(9, 3)).isEmpty();
    }

    @Test
    void aFullPageWithoutPaginationHeadersStillReadsTheNextDiscussionPage() throws Exception {
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", null, "错误没有处理。"));
        webhook("open");
        drafts = List.of();
        discussionOnSecondPage = true;
        missingPaginationHeader = true;
        reply = "回复位于第二页讨论。";

        webhook("update");

        assertThat(lastReview.priorFindings()).singleElement().satisfies(prior ->
                assertThat(prior.replies()).containsExactly("回复位于第二页讨论。"));
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
        assertThat(POSTED).hasSize(1);
    }

    @Test
    void aCompleteEmptyDiffVersionFinishesSuccessfullyWithoutFindings() throws Exception {
        emptyDiffVersion = true;

        webhook("open");

        assertThat(lastReview.diff()).isEqualTo("[]");
        assertThat(POSTED).isEmpty();
        assertThat(findings.findByMergeRequest(9, 3)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT outcome FROM review_record WHERE project_id = 9 AND merge_request_iid = 3",
                String.class)).isEqualTo("SUCCESS");
    }

    @Test
    void aTargetBaseChangeAfterTheModelPreventsAllFindingWrites() throws Exception {
        changeBaseAfterModel = true;
        drafts = List.of(new Reviewer.CommentDraft("renamed.go", 12, null, "缺陷", null, "错误没有处理。"));

        webhook("open");

        assertThat(POSTED).singleElement().satisfies(posted -> {
            assertThat(posted.has("position")).isFalse();
            assertThat(posted.path("body").asText()).contains("审查期间提交已变化");
        });
        assertThat(findings.findByMergeRequest(9, 3)).isEmpty();
    }

    @Test
    void theDiffVersionMustMatchBaseAndStartAsWellAsHead() throws Exception {
        includeOtherVersionFirst = true;

        webhook("open");

        assertThat(lastReview.diff()).contains("+code").doesNotContain("wrong-version");
        assertThat(RECEIVED).noneSatisfy(received -> assertThat(received.path()).endsWith("/versions/2"));
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
            response = "{\"diff_refs\":{\"head_sha\":\"head\",\"base_sha\":\"" + currentBase
                    + "\",\"start_sha\":\"start\"}}";
        }
        else if (path.equals("/api/v4/user")) { response = "{\"id\":42,\"username\":\"review-service\"}"; }
        else if (path.endsWith("/diffs")) {
            response = "[{\"old_path\":\"old.go\",\"new_path\":\"renamed.go\",\"diff\":\"@@ -1 +1 @@\\n-old\\n+new\"}]";
        }
        else if (path.endsWith("/versions")) {
            String current = "{\"id\":1,\"head_commit_sha\":\"head\",\"base_commit_sha\":\"base\",\"start_commit_sha\":\"start\"}";
            response = includeOtherVersionFirst
                    ? "[{\"id\":2,\"head_commit_sha\":\"head\",\"base_commit_sha\":\"old-base\",\"start_commit_sha\":\"old-start\"}," + current + "]"
                    : "[" + current + "]";
        }
        else if (path.endsWith("/versions/1")) {
            response = emptyDiffVersion
                    ? "{\"head_commit_sha\":\"head\",\"base_commit_sha\":\"base\",\"start_commit_sha\":\"start\",\"state\":\"without_files\",\"real_size\":\"0\",\"diffs\":[]}"
                    : "{\"head_commit_sha\":\"head\",\"base_commit_sha\":\"base\",\"start_commit_sha\":\"start\",\"state\":\"collected\",\"real_size\":\"1\","
                      + "\"diffs\":[{\"old_path\":\"old.go\",\"new_path\":\"renamed.go\",\"diff\":\"+code\"}]}";
        }
        else if (path.endsWith("/versions/2")) {
            response = "{\"head_commit_sha\":\"head\",\"base_commit_sha\":\"old-base\",\"start_commit_sha\":\"old-start\",\"state\":\"collected\",\"real_size\":\"1\","
                    + "\"diffs\":[{\"old_path\":\"old.go\",\"new_path\":\"renamed.go\",\"diff\":\"+wrong-version\"}]}";
        }
        else if (path.endsWith("/repository/tree")) { response = "[]"; }
        else if (path.endsWith("/repository/branches/main")) { response = "{\"commit\":{\"id\":\"main\"}}"; }
        else if (path.endsWith("/projects/9")) { response = "{\"default_branch\":\"main\"}"; }
        else if (path.endsWith("/discussions") && method.equals("POST")) {
            POSTED.add(body);
            int number = POSTED.size();
            if (rejectPositionStatus > 0 && body.has("position")) {
                status = rejectPositionStatus;
                response = "{\"message\":\"position line_code is invalid\"}";
            }
            else if (failSecond && number == 2) {
                status = 503;
                response = "{\"message\":\"unavailable\"}";
            }
            else {
                status = 201;
                response = "{\"id\":\"thread-" + (100 + number) + "\",\"notes\":[{\"id\":"
                        + (100 + number) + ",\"author\":{\"id\":42}}]}";
            }
        }
        else if (path.endsWith("/discussions") && method.equals("GET")) {
            if (discussionOnSecondPage && exchange.getRequestURI().getQuery().endsWith("&page=1")) {
                if (missingPaginationHeader) {
                    var discussions = JSON.createArrayNode();
                    for (int index = 0; index < 100; index++) {
                        var item = discussions.addObject().put("id", "unrelated-" + index);
                        item.putArray("notes").addObject().put("id", 1000 + index).put("body", "其他讨论");
                    }
                    response = discussions.toString();
                }
                else {
                    exchange.getResponseHeaders().set("X-Next-Page", "2");
                    response = "[]";
                }
            }
            else {
                var discussion = JSON.createObjectNode().put("id", "thread-101");
                var notes = discussion.putArray("notes");
                notes.addObject().put("id", 101).put("body", "发现").put("resolved", firstDiscussionResolved)
                        .putObject("author").put("id", 42);
                notes.addObject().put("id", 201).put("body", reply).putObject("author").put("id", 7);
                if (includeOwnAndSystemReplies) {
                    notes.addObject().put("id", 202).put("body", "审查服务自己的后续回复：不需要修改。")
                            .putObject("author").put("id", 42);
                    notes.addObject().put("id", 203).put("body", "系统通知").put("system", true)
                            .putObject("author").put("id", 7);
                    notes.addObject().put("id", 204).put("body", "复核后确认需要修改。")
                            .putObject("author").put("id", 8);
                    notes.addObject().put("id", 205).put("body", "补充调用链供参考。")
                            .putObject("author").put("id", 7);
                }
                response = "[" + discussion + "]";
            }
        }
        else if (path.endsWith("/discussions/thread-101") && method.equals("PUT")) {
            firstDiscussionResolved = body.path("resolved").asBoolean();
            response = "{}";
        }
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
            return request -> {
                lastReview = request;
                if (changeBaseAfterModel) { currentBase = "changed-base"; }
                return new Reviewer.ReviewFindings(drafts, resolved);
            };
        }
        @Bean @Primary WorkItemClient transportWorkItems() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker transportSpecPicker() { return paths -> List.of(); }
        @Bean @Primary CodingStandardPicker transportStandardsPicker() { return paths -> List.of(); }
    }
}
