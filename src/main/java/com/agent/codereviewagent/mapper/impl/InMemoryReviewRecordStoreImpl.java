package com.agent.codereviewagent.mapper.impl;

import com.agent.codereviewagent.mapper.ReviewRecordStore;
import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的审查记录，重启后丢失。
 * 审查历史按发生顺序追加；失败概览讨论只记住当前那一条的 note id，用来替换或删除。
 */
@Component
public class InMemoryReviewRecordStoreImpl implements ReviewRecordStore {

    /** 同一合并请求可以有多条。ArrayList 不是线程安全的，读写都加锁。 */
    private final List<ReviewRecord> records = new ArrayList<>();

    /** 合并请求当前失败概览讨论的 note id。键是 projectId 加 iid。 */
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

    /** 测试隔离用，审查历史和失败讨论索引一起清掉。 */
    public synchronized void clear() {
        records.clear();
        failureNotes.clear();
    }

    private static String key(long projectId, long mergeRequestIid) {
        return projectId + ":" + mergeRequestIid;
    }
}
