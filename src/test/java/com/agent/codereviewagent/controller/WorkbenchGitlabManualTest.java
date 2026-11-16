package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.service.Reviewer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从管理员 API 到真实 GitLab 适配器：手动审查子组中新登记的仓库，固定 MR 版本并写回一次。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "review.workbench.token=workbench-test",
        "review.gitlab.project-id=9", "review.gitlab.token=platform-test", "review.standards.branch="})
@AutoConfigureMockMvc
class WorkbenchGitlabManualTest {
    private static final String ROOT = "/api/v4/projects/721";
    private static final String MR = ROOT + "/merge_requests/11";
    private static final String AUTH = "Bearer workbench-test";
    private static final String COMMENT = "空值时应保留已有配置。";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> READ = new CopyOnWriteArrayList<>();
    private static final List<String> POSTS = new CopyOnWriteArrayList<>();
    private static final HttpServer PLATFORM = start();

    @Autowired private MockMvc mvc;
    @MockitoBean private Reviewer reviewer;

    @DynamicPropertySource
    static void platform(DynamicPropertyRegistry registry) {
        registry.add("review.gitlab.base-url", () -> "http://127.0.0.1:" + PLATFORM.getAddress().getPort());
    }

    @AfterAll static void stop() { PLATFORM.stop(0); }

    @Test
    void registersSubgroupAndReviewsItsMergeRequestInsteadOfTheLegacyProject() throws Exception {
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft(null,null,null,"缺陷",null,COMMENT))));
        String url = "https://127.0.0.1:" + PLATFORM.getAddress().getPort() + "/tea/team/cups";
        JsonNode repo = JSON.readTree(mvc.perform(post("/api/repositories").header("Authorization",AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("url",url).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        assertThat(repo.path("platform").asText()).isEqualTo("gitlab");
        assertThat(repo.path("remoteId").asLong()).isEqualTo(721);
        String jobs = "/api/repositories/" + repo.path("id").asLong() + "/jobs";
        JsonNode queued = JSON.readTree(mvc.perform(post(jobs).header("Authorization",AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"number\":11}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray()).path("data");
        long taskId = queued.path("id").asLong();
        assertThat(taskId).isPositive();
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
            JsonNode latest = JSON.readTree(mvc.perform(get(jobs).header("Authorization",AUTH))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data").path(0);
            assertThat(latest.path("id").asLong()).isEqualTo(taskId);
            assertThat(latest.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(latest.path("headSha").asText()).isEqualTo("h");
            assertThat(latest.path("source").asText()).isEqualTo("manual");
        });
        var request = ArgumentCaptor.forClass(Reviewer.ReviewRequest.class);
        verify(reviewer).review(request.capture());
        assertThat(request.getValue().projectId()).isEqualTo(721);
        assertThat(request.getValue().spec().mergeRequestDescription()).isEqualTo("MR body");
        assertThat(request.getValue().diff()).contains("+diff-721");
        assertThat(READ).contains("/api/v4/projects/tea%2Fteam%2Fcups",MR,MR + "/versions/6");
        assertThat(READ).noneMatch(path -> path.startsWith("/api/v4/projects/9/"));
        assertThat(POSTS).containsExactly(COMMENT);
    }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v4/",WorkbenchGitlabManualTest::respond);
            server.start();
            return server;
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        int status = 200;
        String result = "{}";
        if (!"platform-test".equals(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"))) { status = 403; }
        else if ("GET".equals(method)) {
            READ.add(path);
            result = switch (path) {
                case "/api/v4/projects/tea%2Fteam%2Fcups" -> "{\"id\":721,\"path_with_namespace\":\"tea/team/cups\"}";
                case ROOT -> "{\"default_branch\":\"main\"}";
                case ROOT + "/repository/branches/main" -> "{\"commit\":{\"id\":\"stable\"}}";
                case ROOT + "/repository/tree" -> "[]";
                case MR -> "{\"description\":\"MR body\",\"diff_refs\":{\"head_sha\":\"h\",\"base_sha\":\"b\",\"start_sha\":\"s\"}}";
                case MR + "/versions" -> "[{\"id\":6,\"head_commit_sha\":\"h\",\"base_commit_sha\":\"b\",\"start_commit_sha\":\"s\"}]";
                case MR + "/versions/6" -> """
                        {"head_commit_sha":"h","base_commit_sha":"b","start_commit_sha":"s","real_size":"1",
                         "state":"collected","diffs":[{"old_path":"x","new_path":"x","diff":"+diff-721"}]}
                        """;
                default -> null;
            };
            if (result == null) { status = 404; result = "{}"; }
        } else if ("POST".equals(method) && path.equals(MR + "/discussions")) {
            POSTS.add(JSON.readTree(exchange.getRequestBody()).path("body").asText());
            status = 201;
            result = "{\"notes\":[{\"id\":101}]}";
        } else { status = 404; }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
