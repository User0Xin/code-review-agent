package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.mapper.CodingStandardRevisionMapper;
import com.agent.codereviewagent.mapper.FindingMapper;
import com.agent.codereviewagent.mapper.RevisionCheckpointMapper;
import com.agent.codereviewagent.models.entity.CodingStandardRevisionEntity;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.RevisionCheckpointEntity;
import com.agent.codereviewagent.service.RevisionClient;
import com.agent.codereviewagent.service.RevisionRecordService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.Optional;

@Service
public class RevisionRecordServiceImpl implements RevisionRecordService {
    private final CodingStandardRevisionMapper revisions;
    private final RevisionCheckpointMapper checkpoints;
    private final FindingMapper findings;

    public RevisionRecordServiceImpl(CodingStandardRevisionMapper revisions, RevisionCheckpointMapper checkpoints,
                                     FindingMapper findings) {
        this.revisions = revisions;
        this.checkpoints = checkpoints;
        this.findings = findings;
    }

    @Override
    public Optional<CodingStandardRevisionEntity> active(long projectId) {
        return Optional.ofNullable(revisions.selectOne(Wrappers.<CodingStandardRevisionEntity>lambdaQuery()
                .eq(CodingStandardRevisionEntity::getProjectId, projectId)
                .in(CodingStandardRevisionEntity::getState, "PREPARING", "OPEN")));
    }

    @Override
    public RevisionCheckpointEntity checkpoint(long projectId) {
        RevisionCheckpointEntity state = checkpoints.selectById(projectId);
        if (state == null) {
            state = new RevisionCheckpointEntity();
            state.setProjectId(projectId);
            state.setLastFindingId(0L);
            state.setRetryPending(false);
        }
        return state;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkpoint(long projectId, long lastFindingId, boolean retry) {
        checkpoints.checkpoint(projectId, lastFindingId, retry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepare(CodingStandardRevisionEntity revision) {
        revisions.insert(revision);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(CodingStandardRevisionEntity revision, RevisionClient.RemoteRevision remote) {
        revision.setMergeRequestIid(remote.iid());
        revision.setState(state(remote.state()));
        revision.setError(null);
        // updateById 默认跳过 null，显式清理错误字段。
        revisions.update(revision, Wrappers.<CodingStandardRevisionEntity>lambdaUpdate()
                .eq(CodingStandardRevisionEntity::getId, revision.getId())
                .set(CodingStandardRevisionEntity::getError, null));
        var ids = Arrays.stream(revision.getFindingIds().split(",")).map(Long::valueOf).toList();
        findings.update(null, Wrappers.<FindingEntity>lambdaUpdate()
                .eq(FindingEntity::getProjectId, revision.getProjectId())
                .in(FindingEntity::getId, ids)
                .isNull(FindingEntity::getRevisionId)
                .set(FindingEntity::getRevisionId, revision.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveState(CodingStandardRevisionEntity revision) { revisions.updateById(revision); }

    private static String state(String state) {
        return switch (state) {
            case "opened", "locked", "OPEN" -> "OPEN";
            case "closed", "CLOSED" -> "CLOSED";
            case "merged", "MERGED" -> "MERGED";
            default -> throw new IllegalStateException("编码规范修订的远端状态不正确");
        };
    }
}
