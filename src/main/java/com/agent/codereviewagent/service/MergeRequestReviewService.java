package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;

/** 接收 GitLab MR 或已验签的 GitHub PR，复用同一套审查与评论流程。 */
public interface MergeRequestReviewService {

    void accept(String gitlabEvent, MergeRequestWebhookRequest request);

    void acceptGithub(long repositoryId, long number, String action, String description, String headSha);
}
