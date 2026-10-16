package com.agent.codereviewagent.service;

import java.util.Optional;

/** 读取合并请求的 diff，以及写回、删除概览讨论。 */
public interface GitlabClient {

    Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid);

    long createOverviewDiscussion(long projectId, long mergeRequestIid, String body);

    long createLineDiscussion(long projectId, long mergeRequestIid, String path, Integer newLine, Integer oldLine, String headSha, String body);

    void deleteNote(long projectId, long mergeRequestIid, long noteId);

    record DiffRefs(String headSha) {
    }
}
