package com.agent.codereviewagent.service;

/** 每次审查结束后检查整个被审查仓库积累的发现；修订失败记在当次审查记录中。 */
public interface CodingStandardRevisionService {
    void checkAfterReview(long projectId, long mergeRequestIid);
}
