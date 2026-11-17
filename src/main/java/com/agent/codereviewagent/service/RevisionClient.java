package com.agent.codereviewagent.service;

import java.util.Map;
import java.util.Optional;

/** 只提交规范文件并打开修订合并请求，不提供批准或合并操作。 */
public interface RevisionClient {
    /** 按提案保存的源分支查找远端合并请求；即使已关闭或合并，也视为已创建。 */
    Optional<RemoteRevision> findBySourceBranch(long projectId, String sourceBranch);
    RemoteRevision createOrRecover(long projectId, String sourceBranch, String targetBranch,
                                   Map<String, String> files, String title, String description);
    default RemoteRevision createOrRecover(long projectId, String sourceBranch, String targetBranch, String baseRef,
                                           Map<String, String> files, String title, String description) {
        return createOrRecover(projectId, sourceBranch, targetBranch, files, title, description);
    }
    RemoteRevision get(long projectId, long iid);
    record RemoteRevision(long iid, String state) { }
}
