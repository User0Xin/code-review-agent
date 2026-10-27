package com.agent.codereviewagent.models.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** GitLab 合并请求 webhook 中本服务会读取的字段。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MergeRequestWebhookRequest(
        @JsonProperty("object_kind") String objectKind,
        Project project,
        @JsonProperty("object_attributes") ObjectAttributes objectAttributes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(Long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectAttributes(
            String action,
            Long iid,
            String oldrev,
            @JsonProperty("source_branch") String sourceBranch,
            String description,
            @JsonProperty("last_commit") Commit lastCommit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(String id) { }
}
