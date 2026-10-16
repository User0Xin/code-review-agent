package com.agent.codereviewagent.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 审查记录，以及该合并请求当前失败概览讨论的 note id。
 * 审查记录是一次审查的结果，不是发现。
 */
public interface ReviewRecordStore {

    void save(ReviewRecord record);

    List<ReviewRecord> findByMergeRequest(long projectId, long mergeRequestIid);

    Optional<Long> failureNoteId(long projectId, long mergeRequestIid);

    void rememberFailureNote(long projectId, long mergeRequestIid, long noteId);

    void forgetFailureNote(long projectId, long mergeRequestIid);

    record ReviewRecord(long projectId, long mergeRequestIid, Outcome outcome, String reason, Instant occurredAt) {

        public enum Outcome {
            SUCCESS,
            FAILED
        }
    }
}
