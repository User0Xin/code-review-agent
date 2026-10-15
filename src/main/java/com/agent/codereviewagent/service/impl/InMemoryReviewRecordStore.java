package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.service.ReviewRecordStore;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryReviewRecordStore implements ReviewRecordStore {

    private final List<ReviewRecord> records = new ArrayList<>();
    private final ConcurrentHashMap<String, Long> failureNotes = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(ReviewRecord record) {
        records.add(record);
    }

    @Override
    public synchronized List<ReviewRecord> findByMergeRequest(long projectId, long mergeRequestIid) {
        return records.stream()
                .filter(record -> record.projectId() == projectId && record.mergeRequestIid() == mergeRequestIid)
                .toList();
    }

    @Override
    public Optional<Long> failureNoteId(long projectId, long mergeRequestIid) {
        return Optional.ofNullable(failureNotes.get(key(projectId, mergeRequestIid)));
    }

    @Override
    public void rememberFailureNote(long projectId, long mergeRequestIid, long noteId) {
        failureNotes.put(key(projectId, mergeRequestIid), noteId);
    }

    @Override
    public void forgetFailureNote(long projectId, long mergeRequestIid) {
        failureNotes.remove(key(projectId, mergeRequestIid));
    }

    public synchronized void clear() {
        records.clear();
        failureNotes.clear();
    }

    private static String key(long projectId, long mergeRequestIid) {
        return projectId + ":" + mergeRequestIid;
    }
}
