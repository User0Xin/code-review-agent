package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;

/** 处理一条合并请求 webhook：开始审查，或什么都不做。 */
public interface MergeRequestReviewService {

    void accept(String gitlabEvent, MergeRequestWebhookRequest request);
}
