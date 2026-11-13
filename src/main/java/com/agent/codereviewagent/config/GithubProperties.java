package com.agent.codereviewagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 接受事件的仓库、出站 API 凭据与入站签名密钥。两个凭据独立配置。 */
@Component
@ConfigurationProperties(prefix = "review.github")
public class GithubProperties {
    private String baseUrl = "https://api.github.com";
    private String repository;
    private long repositoryId;
    private String token;
    private String webhookSecret;
    private Duration requestTimeout = Duration.ofSeconds(30);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getRepository() { return repository; }
    public void setRepository(String value) { repository = value; }
    public long getRepositoryId() { return repositoryId; }
    public void setRepositoryId(long value) { repositoryId = value; }
    public String getToken() { return token; }
    public void setToken(String value) { token = value; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String value) { webhookSecret = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
}
