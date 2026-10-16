package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.mapper.FindingMapper;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.FindingService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 发现的业务操作。 */
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
}
