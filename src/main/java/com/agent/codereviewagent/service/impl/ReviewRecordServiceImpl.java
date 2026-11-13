package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.mapper.ReviewFailureNoteMapper;
import com.agent.codereviewagent.mapper.ReviewRecordMapper;
import com.agent.codereviewagent.models.entity.ReviewFailureNoteEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 每轮审查各留一条历史记录；另按合并请求保存当前失败通知的编号，供下次审查清理。 */
@Service
public class ReviewRecordServiceImpl
        extends ServiceImpl<ReviewRecordMapper, ReviewRecordEntity>
        implements ReviewRecordService {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";

    private final ReviewFailureNoteMapper reviewFailureNoteMapper;

    public ReviewRecordServiceImpl(ReviewFailureNoteMapper reviewFailureNoteMapper) {
        this.reviewFailureNoteMapper = reviewFailureNoteMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordSuccess(ReviewRecordEntity record) {
        record.setOutcome(SUCCESS);
        save(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFailure(ReviewRecordEntity record, long failureNoteId) {
        recordFailure(record, Long.valueOf(failureNoteId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFailure(ReviewRecordEntity record, Long failureNoteId) {
        record.setOutcome(FAILED);
        record.setFailureNoteId(failureNoteId);
        // 有可追踪的通知时，在同一事务中维护索引和历史记录；没有通知也要保留失败历史。
        if (failureNoteId != null) {
            reviewFailureNoteMapper.upsert(record.getProjectId(), record.getMergeRequestIid(), failureNoteId);
        }
        save(record);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewRecordEntity> findByMergeRequest(long projectId, long mergeRequestIid) {
        return lambdaQuery()
                .eq(ReviewRecordEntity::getProjectId, projectId)
                .eq(ReviewRecordEntity::getMergeRequestIid, mergeRequestIid)
                .orderByAsc(ReviewRecordEntity::getId)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findFailureNoteId(long projectId, long mergeRequestIid) {
        return Optional.ofNullable(reviewFailureNoteMapper.selectOne(
                        Wrappers.<ReviewFailureNoteEntity>lambdaQuery()
                                .eq(ReviewFailureNoteEntity::getProjectId, projectId)
                                .eq(ReviewFailureNoteEntity::getMergeRequestIid, mergeRequestIid)))
                .map(ReviewFailureNoteEntity::getNoteId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forgetFailureNote(long projectId, long mergeRequestIid) {
        reviewFailureNoteMapper.delete(Wrappers.<ReviewFailureNoteEntity>lambdaQuery()
                .eq(ReviewFailureNoteEntity::getProjectId, projectId)
                .eq(ReviewFailureNoteEntity::getMergeRequestIid, mergeRequestIid));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordRevisionFailure(long projectId, long mergeRequestIid, String error) {
        // 修订检查在审查结束后运行；错误附在最近一轮记录上，不改变这一轮的审查结果。
        ReviewRecordEntity latest = lambdaQuery().eq(ReviewRecordEntity::getProjectId, projectId)
                .eq(ReviewRecordEntity::getMergeRequestIid, mergeRequestIid)
                .orderByDesc(ReviewRecordEntity::getId).last("LIMIT 1").one();
        if (latest != null) {
            latest.setSummaryError(error);
            updateById(latest);
        }
    }
}
