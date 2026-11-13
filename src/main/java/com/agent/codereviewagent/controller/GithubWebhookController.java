package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.common.Result;
import com.agent.codereviewagent.config.GithubProperties;
import com.agent.codereviewagent.models.dto.GithubPullRequestWebhookRequest;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
@RequestMapping("/github/webhooks")
public class GithubWebhookController {
    private static final Logger log = LoggerFactory.getLogger(GithubWebhookController.class);
    private static final int MAX_PAYLOAD_BYTES = 25 * 1024 * 1024;
    private static final String SIGNATURE_PREFIX = "sha256=";
    private final GithubProperties properties;
    private final ObjectMapper json;
    private final MergeRequestReviewService review;
    private final TaskExecutor executor;

    public GithubWebhookController(GithubProperties properties, ObjectMapper json, MergeRequestReviewService review,
                                   @Qualifier("githubWebhookExecutor") TaskExecutor executor) {
        this.properties = properties;
        this.json = json;
        this.review = review;
        this.executor = executor;
    }

    @PostMapping(path = "/pull-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> receive(@RequestHeader(name = "X-GitHub-Event", required = false) String event,
                                @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] payload = verifiedPayload(request, signature);
        if (!"pull_request".equals(event)) {
            return Result.success();
        }
        var webhook = parsePullRequest(payload);
        if (webhook == null || !matchesRepository(webhook.repository()) || !triggersReview(webhook.action())) {
            return Result.success();
        }
        var pr = webhook.pullRequest();
        if (webhook.number() == null || webhook.number() <= 0
                || pr == null || pr.head() == null || pr.head().sha() == null || pr.head().sha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub webhook 缺少 PR 编号或提交");
        }
        try {
            executor.execute(() -> startReview(webhook));
        }
        catch (TaskRejectedException saturated) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub 审查队列已满或正在关闭");
        }
        response.setStatus(HttpStatus.ACCEPTED.value());
        return Result.success();
    }

    private byte[] verifiedPayload(HttpServletRequest request, String signature) throws IOException {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub webhook 密钥未配置");
        }
        if (signature == null || signature.length() != SIGNATURE_PREFIX.length() + 64
                || !signature.startsWith(SIGNATURE_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitHub webhook 签名无效");
        }
        // 签名覆盖原始字节，包括空白和 UTF-8 字符，不能先反序列化 DTO 再生成 JSON。
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        byte[] payload = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        if (!validSignature(secret, payload, signature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitHub webhook 签名无效");
        }
        return payload;
    }

    private GithubPullRequestWebhookRequest parsePullRequest(byte[] payload) {
        try {
            return json.readValue(payload, GithubPullRequestWebhookRequest.class);
        }
        catch (IOException invalidJson) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub webhook JSON 无效");
        }
    }

    private boolean matchesRepository(GithubPullRequestWebhookRequest.Repository repository) {
        return repository != null && repository.fullName() != null
                && repository.fullName().equalsIgnoreCase(properties.getRepository())
                && repository.id() != null && repository.id() > 0 && repository.id() == properties.getRepositoryId();
    }

    private void startReview(GithubPullRequestWebhookRequest webhook) {
        var pr = webhook.pullRequest();
        try {
            review.acceptGithub(webhook.repository().id(), webhook.number(), webhook.action(), pr.body(), pr.head().sha());
        }
        catch (RuntimeException failure) {
            // 后台任务失败时交付已确认；只记录定位信息，避免输出请求和凭据。
            log.error("GitHub 审查任务中断：repositoryId={}, PR={}, error={}",
                    webhook.repository().id(), webhook.number(), failure.getClass().getSimpleName());
        }
    }

    private static boolean triggersReview(String action) {
        return "opened".equals(action) || "reopened".equals(action) || "synchronize".equals(action);
    }

    private static boolean validSignature(String secret, byte[] payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload),
                    HexFormat.of().parseHex(signature, SIGNATURE_PREFIX.length(), signature.length()));
        }
        catch (IllegalArgumentException invalidHex) {
            return false;
        }
        catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("无法校验 GitHub webhook 签名", unavailable);
        }
    }
}
