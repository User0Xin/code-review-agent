package com.agent.codereviewagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 被审查仓库的 GitLab 连接；项目编号固定，避免向其他项目写入。 */
@Component
@ConfigurationProperties(prefix = "review.gitlab")
public class GitlabProperties {

    private String baseUrl = "";
    private String token = "";
    private long projectId;
    private Duration requestTimeout = Duration.ofSeconds(30);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public long getProjectId() { return projectId; }
    public void setProjectId(long projectId) { this.projectId = projectId; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
