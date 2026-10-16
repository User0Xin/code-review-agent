package com.agent.codereviewagent.mapper.impl;

import com.agent.codereviewagent.mapper.ReviewRecordStore;
import com.agent.codereviewagent.mapper.sqlite.ReviewFailureNoteMapper;
import com.agent.codereviewagent.mapper.sqlite.ReviewRecordMapper;
import com.agent.codereviewagent.models.entity.ReviewFailureNoteEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 使用 MyBatis-Plus 把审查历史和失败概览索引持久化到 SQLite。 */
@Repository
@RequiredArgsConstructor
public class SqliteReviewRecordStoreImpl implements ReviewRecordStore {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ReviewFailureNoteMapper reviewFailureNoteMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(ReviewRecord record) {
        ReviewRecordEntity entity = new ReviewRecordEntity();
        entity.setProjectId(record.projectId());
        entity.setMergeRequestIid(record.mergeRequestIid());
        entity.setOutcome(record.outcome().name());
        entity.setReason(record.reason());
        entity.setOccurredAt(record.occurredAt().toString());
        reviewRecordMapper.insert(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewRecord> findByMergeRequest(long projectId, long mergeRequestIid) {
        return reviewRecordMapper.selectList(Wrappers.<ReviewRecordEntity>lambdaQuery()
                        .eq(ReviewRecordEntity::getProjectId, projectId)
                        .eq(ReviewRecordEntity::getMergeRequestIid, mergeRequestIid)
                        .orderByAsc(ReviewRecordEntity::getId))
                .stream()
                .map(SqliteReviewRecordStoreImpl::toRecord)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> failureNoteId(long projectId, long mergeRequestIid) {
        return Optional.ofNullable(reviewFailureNoteMapper.selectOne(
                        Wrappers.<ReviewFailureNoteEntity>lambdaQuery()
                                .eq(ReviewFailureNoteEntity::getProjectId, projectId)
                                .eq(ReviewFailureNoteEntity::getMergeRequestIid, mergeRequestIid)))
                .map(ReviewFailureNoteEntity::getNoteId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rememberFailureNote(long projectId, long mergeRequestIid, long noteId) {
        reviewFailureNoteMapper.upsert(projectId, mergeRequestIid, noteId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forgetFailureNote(long projectId, long mergeRequestIid) {
        reviewFailureNoteMapper.delete(Wrappers.<ReviewFailureNoteEntity>lambdaQuery()
                .eq(ReviewFailureNoteEntity::getProjectId, projectId)
                .eq(ReviewFailureNoteEntity::getMergeRequestIid, mergeRequestIid));
    }

    private static ReviewRecord toRecord(ReviewRecordEntity entity) {
        return new ReviewRecord(
                entity.getProjectId(),
                entity.getMergeRequestIid(),
                ReviewRecord.Outcome.valueOf(entity.getOutcome()),
                entity.getReason(),
                Instant.parse(entity.getOccurredAt()));
    }
}
