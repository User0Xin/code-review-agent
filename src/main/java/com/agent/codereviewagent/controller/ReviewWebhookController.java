package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.common.Result;
import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.agent.codereviewagent.service.RepositoryProfiles;
import com.agent.codereviewagent.service.WorkbenchJobs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作台仓库校验事件密钥后登记任务；未登记的仓库沿用原同步处理。 */
@RestController
@ConditionalOnProperty(name = "review.gitlab.webhook-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/gitlab/webhooks")
public class ReviewWebhookController {

    private final MergeRequestReviewService review;
    private final RepositoryProfiles repositories;
    private final WorkbenchJobs jobs;
    private final String secret;

    public ReviewWebhookController(MergeRequestReviewService review, RepositoryProfiles repositories,
                                   WorkbenchJobs jobs,
                                   @Value("${review.gitlab.webhook-secret:${REVIEW_GITLAB_WEBHOOK_SECRET:}}") String secret) {
        this.review = review;
        this.repositories = repositories;
        this.jobs = jobs;
        this.secret = secret;
    }

    @PostMapping("/merge-requests")
    public Result<Void> receive(
            @RequestHeader(value = "X-Gitlab-Event", required = false) String gitlabEvent,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestBody(required = false) MergeRequestWebhookRequest request, HttpServletResponse response) {
        if (!secret.isBlank() && (token == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitLab webhook 密钥无效");
        }
        if ("Merge Request Hook".equals(gitlabEvent) && request != null && "merge_request".equals(request.objectKind())
                && request.project() != null && request.project().id() != null && request.objectAttributes() != null) {
            var profile = repositories.find("gitlab", request.project().id());
            if (profile.isPresent()) {
                if (secret.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "已登记的仓库须设置 GitLab webhook 密钥");
                }
                var attr = request.objectAttributes();
                if (attr.iid() != null && attr.iid() > 0 && attr.startsReview()) {
                    if (jobs.webhook(profile.get(), attr.iid(), attr.lastCommit() == null ? null : attr.lastCommit().id()) != null) {
                        response.setStatus(HttpStatus.ACCEPTED.value());
                    }
                }
                return Result.success();
            }
        }
        review.accept(gitlabEvent, request);
        return Result.success();
    }
}
