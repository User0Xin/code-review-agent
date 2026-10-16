package com.agent.codereviewagent.mapper.impl;

import com.agent.codereviewagent.mapper.FindingStore;
import com.agent.codereviewagent.mapper.sqlite.FindingMapper;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 把已写回的发现持久化到 SQLite。 */
@Repository
@RequiredArgsConstructor
public class SqliteFindingStoreImpl implements FindingStore {

    private final FindingMapper findingMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Finding finding) {
        FindingEntity entity = new FindingEntity();
        entity.setProjectId(finding.projectId());
        entity.setMergeRequestIid(finding.mergeRequestIid());
        entity.setDiscussionId(finding.discussionId());
        entity.setPath(finding.path());
        entity.setNewLine(finding.newLine());
        entity.setOldLine(finding.oldLine());
        entity.setCategory(finding.category());
        entity.setCitation(finding.citation());
        entity.setCommentBody(finding.commentBody());
        entity.setHeadSha(finding.headSha());
        findingMapper.insert(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Finding> findByMergeRequest(long projectId, long mergeRequestIid) {
        return findingMapper.selectList(Wrappers.<FindingEntity>lambdaQuery()
                        .eq(FindingEntity::getProjectId, projectId)
                        .eq(FindingEntity::getMergeRequestIid, mergeRequestIid)
                        .orderByAsc(FindingEntity::getId))
                .stream()
                .map(SqliteFindingStoreImpl::toFinding)
                .toList();
    }

    private static Finding toFinding(FindingEntity entity) {
        return new Finding(
                entity.getProjectId(),
                entity.getMergeRequestIid(),
                entity.getDiscussionId(),
                entity.getPath(),
                entity.getNewLine(),
                entity.getOldLine(),
                entity.getCategory(),
                entity.getCitation(),
                entity.getCommentBody(),
                entity.getHeadSha());
    }
}
