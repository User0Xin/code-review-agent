package com.agent.codereviewagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 飞书项目插件凭据及审查服务专用账号，空间固定于配置。 */
@Component
@ConfigurationProperties(prefix = "review.feishu-project")
public class FeishuProjectProperties {

    private String baseUrl = "https://project.feishu.cn";
    private String projectKey = "";
    private String pluginId = "";
    private String pluginSecret = "";
    private String userKey = "";
    private Duration requestTimeout = Duration.ofSeconds(30);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getPluginId() { return pluginId; }
    public void setPluginId(String pluginId) { this.pluginId = pluginId; }
    public String getPluginSecret() { return pluginSecret; }
    public void setPluginSecret(String pluginSecret) { this.pluginSecret = pluginSecret; }
    public String getUserKey() { return userKey; }
    public void setUserKey(String userKey) { this.userKey = userKey; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
