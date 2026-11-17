package com.agent.codereviewagent.config;

import com.agent.codereviewagent.integration.github.GithubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 diff 等待策略与规范分支的配置。 */
@Configuration
@EnableConfigurationProperties({DiffWaitProperties.class, StandardsBranchProperties.class})
public class ReviewConfiguration {
    @Bean
    GithubClient githubClient(GithubProperties properties, ObjectMapper json) {
        return new GithubClient(properties, json);
    }
}
