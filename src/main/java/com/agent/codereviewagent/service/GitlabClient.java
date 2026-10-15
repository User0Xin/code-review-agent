package com.agent.codereviewagent.service;

import java.util.Optional;

public interface GitlabClient {

    Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid);

    long createOverviewDiscussion(long projectId, long mergeRequestIid, String body);

    void deleteNote(long projectId, long mergeRequestIid, long noteId);

    record DiffRefs(String headSha) {
    }
}
