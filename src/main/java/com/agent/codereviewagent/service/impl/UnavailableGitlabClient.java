package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.service.GitlabClient;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class UnavailableGitlabClient implements GitlabClient {

    @Override
    public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }

    @Override
    public void deleteNote(long projectId, long mergeRequestIid, long noteId) {
        throw new IllegalStateException("GitLab 客户端尚未配置");
    }
}
