package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;
import java.util.Optional;

/** 管理审查记录及当前失败概览讨论的索引。 */
public interface ReviewRecordService extends IService<ReviewRecordEntity> {

    void recordSuccess(ReviewRecordEntity record);

    void recordFailure(ReviewRecordEntity record, long failureNoteId);

    /** 没有可用的失败评论 ID 时传 null，仍须保存本次审查失败的原因。 */
    void recordFailure(ReviewRecordEntity record, Long failureNoteId);

    List<ReviewRecordEntity> findByMergeRequest(long projectId, long mergeRequestIid);

    Optional<Long> findFailureNoteId(long projectId, long mergeRequestIid);

    /** 远端评论删除成功后清理本地索引，保留历史审查记录。 */
    void forgetFailureNote(long projectId, long mergeRequestIid);

    void recordRevisionFailure(long projectId, long mergeRequestIid, String error);
}
