package com.agent.codereviewagent.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 用同一份 SQLite 和远端评论集合走完「失败、手删通知、再次失败、恢复」四步。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:", "review.github.repository=owner/repo",
        "review.github.repository-id=42", "review.github.token=test", "review.diff-wait.max-attempts=1"
})
class GithubFailureNoteRecoveryTest {
    private static final String REPO = "/repos/owner/repo";
    private static final Set<Long> COMMENTS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong LAST_COMMENT = new AtomicLong(100);
    private static HttpServer server;

    @Autowired private MergeRequestReviewService service;
    @Autowired private ReviewRecordService records;
    @MockitoBean private Reviewer reviewer;
    @MockitoBean private CodingStandardPicker standardsPicker;
    @MockitoBean private SpecDocumentPicker specPicker;

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(REPO, GithubFailureNoteRecoveryTest::respond);
        server.start();
        registry.add("review.github.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stop() { if (server != null) { server.stop(0); } }

    @Test
    void replacesManuallyDeletedFailureNoteAndCleansUpReplacementAfterSuccess() {
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.none());
        service.acceptGithub(42, 7, "opened", "", "not-ready");
        long first = LAST_COMMENT.get();
        assertThat(COMMENTS).containsExactly(first);
        long oldIndex = records.findFailureNoteId(-42, 7).orElseThrow();

        COMMENTS.remove(first); // 维护者从 GitHub 会话中删除了旧通知。
        service.acceptGithub(42, 7, "synchronize", "", "not-ready");
        assertThat(COMMENTS).containsExactly(first + 1);
        assertThat(records.findFailureNoteId(-42, 7).orElseThrow()).isNotEqualTo(oldIndex);

        service.acceptGithub(42, 7, "synchronize", "", "head");
        assertThat(records.findByMergeRequest(-42, 7)).extracting("outcome")
                .containsExactly("FAILED", "FAILED", "SUCCESS");
        assertThat(records.findByMergeRequest(-42, 7)).allMatch(r -> r.getNoteError() == null);
        assertThat(records.findFailureNoteId(-42, 7)).isEmpty();
        assertThat(COMMENTS).isEmpty();
    }

    private static void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath().substring(REPO.length());
            String method = exchange.getRequestMethod();
            String data;
            int status = 200;
            if (method.equals("GET") && path.equals("/pulls/7")) {
                data = "{\"head\":{\"sha\":\"head\"},\"base\":{\"sha\":\"base\"},\"changed_files\":0}";
            } else if (method.equals("POST") && path.equals("/issues/7/comments")) {
                long id = LAST_COMMENT.incrementAndGet();
                COMMENTS.add(id);
                data = "{\"id\":" + id + "}";
                status = 201;
            } else if (method.equals("DELETE") && path.startsWith("/issues/comments/")) {
                status = COMMENTS.remove(Long.parseLong(path.substring("/issues/comments/".length()))) ? 204 : 404;
                data = "";
            } else if (method.equals("GET") && path.equals("/issues/7/comments")) {
                data = COMMENTS.stream().map(id -> "{\"id\":" + id + "}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            } else if (method.equals("GET") && path.isEmpty()) {
                data = "{\"default_branch\":\"main\"}";
            } else if (method.equals("GET") && path.equals("/commits/main")) {
                data = "{\"sha\":\"base\"}";
            } else if (method.equals("GET") && path.startsWith("/git/trees/")) {
                data = "{\"truncated\":false,\"tree\":[]}";
            } else {
                status = 500;
                data = "Unexpected fixture route: " + method + " " + path;
            }
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length != 0) { exchange.getResponseBody().write(bytes); }
        }
    }
}
