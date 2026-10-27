package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.CodingStandardRevisionEntity;
import com.agent.codereviewagent.models.entity.RevisionCheckpointEntity;
import java.util.Optional;

public interface RevisionRecordService {
    Optional<CodingStandardRevisionEntity> active(long projectId);
    RevisionCheckpointEntity checkpoint(long projectId);
    void checkpoint(long projectId, long lastFindingId, boolean retry);
    void prepare(CodingStandardRevisionEntity revision);
    void complete(CodingStandardRevisionEntity revision, RevisionClient.RemoteRevision remote);
    void saveState(CodingStandardRevisionEntity revision);
}
