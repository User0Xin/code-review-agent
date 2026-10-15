package com.agent.codereviewagent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
