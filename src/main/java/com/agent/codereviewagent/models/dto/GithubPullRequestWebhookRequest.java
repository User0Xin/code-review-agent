package com.agent.codereviewagent.models.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** GitHub 仓库 webhook 的目标是顶层 repository；来自 fork 的 PR 也使用这个仓库。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubPullRequestWebhookRequest(
        String action,
        Long number,
        Repository repository,
        @JsonProperty("pull_request") PullRequest pullRequest) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(Long id, @JsonProperty("full_name") String fullName) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(String body, Head head) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Head(String sha) { }
}
