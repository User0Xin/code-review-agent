package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.common.Result;
import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 同步处理 GitLab 合并请求事件。正常返回 200 表示事件处理结束；审查是否成功以审查记录为准。 */
@RestController
@ConditionalOnProperty(name = "review.gitlab.webhook-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/gitlab/webhooks")
public class ReviewWebhookController {

    private final MergeRequestReviewService review;

    public ReviewWebhookController(MergeRequestReviewService review) {
        this.review = review;
    }

    @PostMapping("/merge-requests")
    public Result<Void> receive(
            @RequestHeader(value = "X-Gitlab-Event", required = false) String gitlabEvent,
            @RequestBody(required = false) MergeRequestWebhookRequest request) {
        review.accept(gitlabEvent, request);
        return Result.success();
    }
}
