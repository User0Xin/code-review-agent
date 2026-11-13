package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.mapper.FindingMapper;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.FalsePositiveJudge;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 保存已写回 GitLab 的审查发现，供后续复审对照及编码规范修订使用。 */
@Service
public class FindingServiceImpl
        extends ServiceImpl<FindingMapper, FindingEntity>
        implements FindingService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordPostedFinding(FindingEntity finding) {
        save(finding);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FindingEntity> findByMergeRequest(long projectId, long mergeRequestIid) {
        return lambdaQuery()
                .eq(FindingEntity::getProjectId, projectId)
                .eq(FindingEntity::getMergeRequestIid, mergeRequestIid)
                .orderByAsc(FindingEntity::getId)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FindingEntity> findUnconsumed(long projectId) {
        // 规范修订从整个项目汇集尚未归入修订的发现；针对具体需求的 spec 问题不参与。
        return lambdaQuery().eq(FindingEntity::getProjectId, projectId)
                .isNull(FindingEntity::getRevisionId)
                .in(FindingEntity::getCategory, "编码规范", "缺陷")
                .orderByAsc(FindingEntity::getId).list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFalsePositive(FindingEntity finding, FalsePositiveJudge.Decision decision) {
        // 写入时再次检查修订编号，避免覆盖已归入修订的发现所用的判断。
        lambdaUpdate().eq(FindingEntity::getId, finding.getId()).isNull(FindingEntity::getRevisionId)
                .set(FindingEntity::getFalsePositive, decision.falsePositive())
                .set(FindingEntity::getFalsePositiveReason, decision.reason()).update();
        finding.setFalsePositive(decision.falsePositive());
        finding.setFalsePositiveReason(decision.reason());
    }
}
