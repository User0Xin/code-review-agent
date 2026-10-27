package com.agent.codereviewagent.service;

import java.util.List;
import java.util.Optional;

/** 读取合并请求的 diff，以及写回、删除概览讨论。 */
public interface GitlabClient {

    Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid);

    default String readDiff(long projectId, long mergeRequestIid, String headSha) {
        return "";
    }

    long createOverviewDiscussion(long projectId, long mergeRequestIid, String body);

    long createLineDiscussion(long projectId, long mergeRequestIid, String path, Integer newLine, Integer oldLine, String headSha, String body);

    void deleteNote(long projectId, long mergeRequestIid, long noteId);

    List<String> listReplies(long projectId, long mergeRequestIid, long discussionId);

    default boolean isDiscussionResolved(long projectId, long mergeRequestIid, long discussionId) {
        return false;
    }

    void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId);

    List<String> listFiles(long projectId, String ref);

    String readFile(long projectId, String path, String ref);

    String defaultBranch(long projectId);

    default String branchHead(long projectId, String branch) { return branch; }

    record DiffRefs(String headSha) {
    }
}
