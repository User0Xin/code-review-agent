package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;
import java.util.Optional;

/** 管理审查记录及当前失败概览讨论的索引。 */
public interface ReviewRecordService extends IService<ReviewRecordEntity> {

    void recordSuccess(ReviewRecordEntity record);

    void recordFailure(ReviewRecordEntity record, long failureNoteId);

    void recordFailure(ReviewRecordEntity record, Long failureNoteId);

    List<ReviewRecordEntity> findByMergeRequest(long projectId, long mergeRequestIid);

    Optional<Long> findFailureNoteId(long projectId, long mergeRequestIid);

    void forgetFailureNote(long projectId, long mergeRequestIid);

    void recordRevisionFailure(long projectId, long mergeRequestIid, String error);
}
