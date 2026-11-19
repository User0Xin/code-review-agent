package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;

/** 接收 GitLab MR 或已验签的 GitHub PR，复用同一套审查与评论流程。 */
public interface MergeRequestReviewService {

    void accept(String gitlabEvent, MergeRequestWebhookRequest request);

    void acceptGithub(long repositoryId, long number, String action, String description, String headSha);

    /** 已取得平台身份的工作台审查；直接返回这一轮真实入库的记录。 */
    com.agent.codereviewagent.models.entity.ReviewRecordEntity runConfigured(long profileId, long number, String trigger,
            String description, RepositoryClient.DiffRefs expectedRefs);

    com.agent.codereviewagent.models.entity.ReviewRecordEntity runConfigured(long profileId, long number, String trigger,
            String description, RepositoryClient.DiffRefs expectedRefs, long jobId);
}
