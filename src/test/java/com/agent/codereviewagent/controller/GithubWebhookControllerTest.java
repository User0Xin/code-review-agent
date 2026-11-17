package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.config.GithubProperties;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GithubWebhookControllerTest {
    private static final String SECRET = "It's a Secret to Everybody";
    private static final String REPOSITORY = "User0Xin/code-review-agent";
    private static final String PAYLOAD = """
            {"action":"opened","number":9,
             "repository":{"id":42,"full_name":"User0Xin/code-review-agent"},
             "pull_request":{"body":"审查说明","head":{"sha":"abc123","repo":{"full_name":"fork/project"}}}}
            """;
    private final GithubProperties properties = new GithubProperties();
    private final MergeRequestReviewService review = mock(MergeRequestReviewService.class);
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties.setWebhookSecret(SECRET);
        properties.setRepository(REPOSITORY);
        properties.setRepositoryId(42L);
        mvc = mockMvc(queue::add);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opened", "reopened", "synchronize"})
    void signedPullRequestQueuesTheBaseRepositoryAndExactHead(String action) throws Exception {
        String payload = PAYLOAD.replace("opened", action);
        send("pull_request", payload, sign(payload)).andExpect(status().isAccepted());

        // 在 HTTP 返回后才执行审查；测试可控地推进队列。
        verifyNoInteractions(review);
        assertThat(queue).hasSize(1);
        queue.remove().run();
        verify(review).acceptGithub(42, 9, action, "审查说明", "abc123");
    }

    @Test
    void checksSignatureBeforeParsingOrDispatching() throws Exception {
        send("pull_request", PAYLOAD, null).andExpect(status().isForbidden());
        send("pull_request", PAYLOAD + " ", sign(PAYLOAD)).andExpect(status().isForbidden());
        send("pull_request", "{", "sha256=" + "x".repeat(64)).andExpect(status().isForbidden());
        send("pull_request", "{", sign("{")).andExpect(status().isBadRequest());
        assertThat(queue).isEmpty();
    }

    @Test
    void acceptsSignedPingWithoutSchedulingAndFailsClosedWithoutASecret() throws Exception {
        // GitHub 文档提供的独立签名向量。
        String signature = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
        send("ping", "Hello, World!", signature).andExpect(status().isOk());
        properties.setWebhookSecret("");
        send("ping", "Hello, World!", signature).andExpect(status().isServiceUnavailable());
        assertThat(queue).isEmpty();
    }

    @Test
    void unrelatedRepositoriesActionsAndEventsAreIgnored() throws Exception {
        for (String payload : new String[] {
                PAYLOAD.replace("42", "43"),
                PAYLOAD.replace(REPOSITORY, "another/project"),
                PAYLOAD.replace("opened", "edited"),
                PAYLOAD.replace("opened", "closed")}) {
            send("pull_request", payload, sign(payload)).andExpect(status().isOk());
        }
        send("issue_comment", PAYLOAD, sign(PAYLOAD)).andExpect(status().isOk());
        assertThat(queue).isEmpty();
    }

    @Test
    void rejectsMissingHeadAndReportsQueueSaturation() throws Exception {
        String noHead = PAYLOAD.replace("abc123", "");
        send("pull_request", noHead, sign(noHead)).andExpect(status().isBadRequest());
        mvc = mockMvc(task -> { throw new TaskRejectedException("queue full"); });
        send("pull_request", PAYLOAD, sign(PAYLOAD)).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(review);
    }

    private MockMvc mockMvc(TaskExecutor executor) {
        return MockMvcBuilders.standaloneSetup(
                new GithubWebhookController(properties, new ObjectMapper(), review, executor)).build();
    }

    private ResultActions send(String event, String payload, String signature) throws Exception {
        var request = post("/github/webhooks/pull-requests")
                .header("X-GitHub-Event", event)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return mvc.perform(request);
    }

    private static String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
