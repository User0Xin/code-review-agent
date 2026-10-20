package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.service.GitlabClient;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 还没接上真实 GitLab 时的占位。调用即失败，避免被当成 diff 尚未就绪。 */
@Component
public class UnavailableGitlabClientImpl implements GitlabClient {

    @Override
    public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public long createLineDiscussion(long projectId, long mergeRequestIid, String path, Integer newLine, Integer oldLine, String headSha, String body) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public void deleteNote(long projectId, long mergeRequestIid, long noteId) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public List<String> listReplies(long projectId, long mergeRequestIid, long discussionId) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public List<String> listFiles(long projectId, String ref) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public String readFile(long projectId, String path, String ref) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public String defaultBranch(long projectId) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }
}
