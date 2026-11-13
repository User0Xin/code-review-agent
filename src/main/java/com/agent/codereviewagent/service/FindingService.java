package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

/** 管理已经写回合并请求的发现。 */
public interface FindingService extends IService<FindingEntity> {

    void recordPostedFinding(FindingEntity finding);

    List<FindingEntity> findByMergeRequest(long projectId, long mergeRequestIid);

    /** 按项目取尚未用于修订的编码规范和缺陷类发现；即使上次判为误报，也要结合最新回复重新判断。 */
    List<FindingEntity> findUnconsumed(long projectId);

    void recordFalsePositive(FindingEntity finding, FalsePositiveJudge.Decision decision);
}
