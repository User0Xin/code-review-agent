package com.agent.codereviewagent.service;

/** 审查结束时检查积累的发现。总结失败只留在内部记录中。 */
public interface CodingStandardRevisionService {
    void checkAfterReview(long projectId, long mergeRequestIid);
}
