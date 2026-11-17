package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.CodingStandardRevisionEntity;
import com.agent.codereviewagent.models.entity.RevisionCheckpointEntity;
import java.util.Optional;

/** 保存项目的修订提案及总结进度，供下一次审查继续处理。 */
public interface RevisionRecordService {
    /** 返回已准备但未确认发布、或已在远端打开的提案。 */
    Optional<CodingStandardRevisionEntity> active(long projectId);
    RevisionCheckpointEntity checkpoint(long projectId);
    void checkpoint(long projectId, long lastFindingId, boolean retry);
    /** 保存本批符合条件的发现 ID，重试时用它们重新核对数量门槛。 */
    void retryBasis(long projectId, String findingIds);
    void prepare(CodingStandardRevisionEntity revision);
    /** 同步远端状态，并将提案实际采用的发现关联到修订；不要求远端已经合并。 */
    void complete(CodingStandardRevisionEntity revision, RevisionClient.RemoteRevision remote);
    void saveState(CodingStandardRevisionEntity revision);
}
