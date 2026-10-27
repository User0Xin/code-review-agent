package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.SpecDocumentPicker;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 webhook 验证真实飞书项目 HTTP 适配器，不使用真实凭据或远端工作项。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FeishuSpecWebhookTest.Fakes.class)
class FeishuSpecWebhookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GitlabClient GITLAB = mock(GitlabClient.class);
    private static final AtomicReference<Reviewer.ReviewRequest> REVIEW = new AtomicReference<>();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final HttpServer SERVER = startServer();
    private static volatile boolean missing;
    private static volatile boolean simpleDescription;
    private static volatile String description = "工作项要求按部门导出";

    @Autowired private MockMvc mvc;
    @Autowired private ReviewRecordService records;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("review.feishu-project.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("review.feishu-project.project-key", () -> "configured-space");
        registry.add("review.feishu-project.plugin-id", () -> "test-plugin");
        registry.add("review.feishu-project.plugin-secret", () -> "test-secret");
        registry.add("review.feishu-project.user-key", () -> "review-account");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:target/feishu-spec-test.db");
    }

    @BeforeEach
    void prepare() {
        reset(GITLAB);
        when(GITLAB.diffRefs(9, 3)).thenReturn(Optional.of(new GitlabClient.DiffRefs("head")));
        when(GITLAB.listFiles(anyLong(), anyString())).thenReturn(List.of());
        when(GITLAB.defaultBranch(9)).thenReturn("main");
        REVIEW.set(null);
        RECEIVED.clear();
        missing = false;
        simpleDescription = false;
        description = "工作项要求按部门导出";
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM review_failure_note");
    }

    @AfterAll
    static void stop() { SERVER.stop(0); }

    @Test
    void webhookReadsDocTextAcrossAllTypesWithTheDedicatedAccountAndRefreshesSpec() throws Exception {
        webhook("open");

        assertThat(REVIEW.get().spec().workItemText()).isEqualTo("工作项要求按部门导出");
        assertThat(REVIEW.get().spec().skipped()).isFalse();
        assertThat(records.findByMergeRequest(9, 3)).singleElement()
                .extracting(ReviewRecordEntity::getOutcome).isEqualTo("SUCCESS");
        assertThat(RECEIVED).filteredOn(received -> received.path().endsWith("/filter"))
                .singleElement().satisfies(received -> {
                    assertThat(received.method()).isEqualTo("POST");
                    assertThat(received.body().path("work_item_ids").get(0).asLong()).isEqualTo(7058321625L);
                    assertThat(received.body().path("work_item_type_keys")).hasSize(2);
                    assertThat(received.body().path("expand").path("need_multi_text").asBoolean()).isTrue();
                    assertThat(received.token()).isEqualTo("plugin-token");
                    assertThat(received.userKey()).isEqualTo("review-account");
                    assertThat(received.authMode()).isEqualTo("1");
                });
        assertThat(RECEIVED).filteredOn(received -> received.path().endsWith("/all-types"))
                .singleElement().satisfies(received -> assertThat(received.method()).isEqualTo("GET"));

        description = "第二版工作项要求导出所有部门";
        webhook("update");

        assertThat(REVIEW.get().spec().workItemText()).isEqualTo("第二版工作项要求导出所有部门");
        assertThat(TOKEN_REQUESTS.get()).isEqualTo(1);
        verify(GITLAB, never()).createOverviewDiscussion(anyLong(), anyLong(), anyString());
    }

    @Test
    void aSuccessfulLookupWithNoWorkItemSkipsSpecWithoutAComment() throws Exception {
        missing = true;

        webhook("open");

        assertThat(REVIEW.get().spec().skipped()).isTrue();
        assertThat(REVIEW.get().spec().workItemText()).isNull();
        assertThat(records.findByMergeRequest(9, 3)).singleElement().satisfies(record -> {
            assertThat(record.getOutcome()).isEqualTo("SUCCESS");
            assertThat(record.getSpecSkipped()).isTrue();
        });
        verify(GITLAB, never()).createOverviewDiscussion(anyLong(), anyLong(), anyString());
    }

    @Test
    void aPlainDescriptionFieldAlsoEntersTheReviewWhenNoExpandedTextIsReturned() throws Exception {
        simpleDescription = true;

        webhook("open");

        assertThat(REVIEW.get().spec().workItemText()).isEqualTo(description);
        assertThat(REVIEW.get().spec().skipped()).isFalse();
    }

    private void webhook(String action) throws Exception {
        mvc.perform(post("/gitlab/webhooks/merge-requests").header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"object_kind":"merge_request","project":{"id":9},"object_attributes":
                                  {"iid":3,"action":"%s","oldrev":"previous",
                                   "source_branch":"feature/7058321625-export","description":"描述仅作补充"}}
                                """.formatted(action)))
                .andExpect(status().isOk());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/open_api", FeishuSpecWebhookTest::respond);
            server.start();
            return server;
        }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] input = exchange.getRequestBody().readAllBytes();
        JsonNode body = input.length == 0 ? JSON.nullNode() : JSON.readTree(input);
        RECEIVED.add(new Received(exchange.getRequestMethod(), path,
                exchange.getRequestHeaders().getFirst("X-PLUGIN-TOKEN"),
                exchange.getRequestHeaders().getFirst("X-USER-KEY"),
                exchange.getRequestHeaders().getFirst("x-auth-mode"), body));
        String response;
        int status = 200;
        if (path.equals("/open_api/authen/plugin_token")) {
            TOKEN_REQUESTS.incrementAndGet();
            if (!"test-plugin".equals(body.path("plugin_id").asText())
                    || !"test-secret".equals(body.path("plugin_secret").asText())) {
                status = 401;
            }
            response = "{\"err_code\":0,\"data\":{\"token\":\"plugin-token\",\"expire_time\":7200}}";
        }
        else if (path.equals("/open_api/configured-space/work_item/all-types")) {
            response = "{\"err_code\":0,\"data\":[{\"type_key\":\"story\"},{\"type_key\":\"custom-type\"}]}";
        }
        else if (path.equals("/open_api/configured-space/work_item/filter")) {
            if (missing) { response = "{\"err_code\":0,\"data\":[]}"; }
            else {
                var item = JSON.createObjectNode().put("id", 7058321625L);
                if (simpleDescription) {
                    item.putArray("fields").addObject().put("field_key", "description").put("field_value", description);
                }
                else {
                    item.putArray("multi_texts").addObject().put("field_key", "description")
                            .putObject("field_value").put("doc_text", description).put("doc_html", "<p>不用 HTML</p>");
                }
                var output = JSON.createObjectNode().put("err_code", 0);
                output.putArray("data").add(item);
                response = output.toString();
            }
        }
        else { status = 404; response = "{}"; }
        byte[] output = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, output.length);
        exchange.getResponseBody().write(output);
        exchange.close();
    }

    record Received(String method, String path, String token, String userKey, String authMode, JsonNode body) { }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary GitlabClient feishuGitlab() { return GITLAB; }
        @Bean @Primary Reviewer feishuReviewer() {
            return request -> { REVIEW.set(request); return Reviewer.ReviewFindings.none(); };
        }
        @Bean @Primary SpecDocumentPicker feishuSpecPicker() { return paths -> List.of(); }
        @Bean @Primary CodingStandardPicker feishuStandardsPicker() { return paths -> List.of(); }
    }
}
