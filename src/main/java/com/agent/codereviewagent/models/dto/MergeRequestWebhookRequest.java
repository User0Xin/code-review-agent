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

    /** iid 是项目内的合并请求编号；更新事件用 oldrev 判断是否有代码变动，用 lastCommit 核对待审查的提交。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectAttributes(
            String action,
            Long iid,
            String oldrev,
            String description,
            @JsonProperty("last_commit") Commit lastCommit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(String id) { }
}
