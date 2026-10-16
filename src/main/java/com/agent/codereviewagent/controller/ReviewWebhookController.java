package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.common.Result;
import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 接收 GitLab 合并请求 webhook。无论是否开始审查都返回 200，避免 GitLab 重试。 */
@RestController
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
