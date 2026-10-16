package com.agent.codereviewagent.mapper;

import java.util.List;

/** 已写回合并请求的发现。没写回的内容不是发现。 */
public interface FindingStore {

    void save(Finding finding);

    List<Finding> findByMergeRequest(long projectId, long mergeRequestIid);

    record Finding(
            long projectId,
            long mergeRequestIid,
            long discussionId,
            String path,
            Integer newLine,
            Integer oldLine,
            String category,
            String citation,
            String commentBody,
            String headSha) {
    }
}
