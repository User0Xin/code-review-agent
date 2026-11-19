package com.agent.codereviewagent.integration.gitlab;

import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.service.RepositoryClient;
import com.agent.codereviewagent.service.VersionCheckFailedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitlabPublicationCheckTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void failedVersionReadsBeforeLineMetadataOrFallbackNeverImplyAnUnknownPost(int failureStep) throws Exception {
        var reads = new AtomicInteger();
        var posts = new AtomicInteger();
        var unexpected = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String mr = "/api/v4/projects/7/merge_requests/4";
        String refs = "\"head_commit_sha\":\"head\",\"base_commit_sha\":\"base\",\"start_commit_sha\":\"start\"";
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            if ("POST".equals(exchange.getRequestMethod()) && path.equals(mr + "/discussions")) {
                posts.incrementAndGet();
                status = 422;
                body = "{\"message\":\"invalid position\"}";
            } else if (path.equals(mr)) {
                if (failureStep == 0 || reads.incrementAndGet() > 1) {
                    status = 500; body = "{}";
                } else {
                    body = "{\"diff_refs\":{\"head_sha\":\"head\",\"base_sha\":\"base\",\"start_sha\":\"start\"}}";
                }
            } else if (path.equals(mr + "/versions")) {
                if (failureStep == 1) { status = 500; body = "{}"; }
                else { body = "[{\"id\":6," + refs + "}]"; }
            } else if (path.equals(mr + "/versions/6")) {
                body = "{" + refs + ",\"real_size\":\"1\",\"state\":\"collected\","
                        + "\"diffs\":[{\"old_path\":\"App.java\",\"new_path\":\"App.java\"}]}";
            } else { unexpected.incrementAndGet(); status = 500; body = "{}"; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var properties = new GitlabProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setProjectId(7); properties.setToken("test-token");
            var client = new RestGitlabClient(properties, new ObjectMapper());
            assertThatThrownBy(() -> client.publishLine(7, 4, "App.java", 2, null,
                    new RepositoryClient.DiffRefs("head", "base", "start"), "body"))
                    .isInstanceOf(VersionCheckFailedException.class);
            // 仅回退场景曾发送一次明确被拒绝的行评论，其余场景完全没有发送。
            assertThat(posts.get()).isEqualTo(failureStep == 2 ? 1 : 0);
            assertThat(unexpected.get()).isZero();
        } finally { server.stop(0); }
    }
}
