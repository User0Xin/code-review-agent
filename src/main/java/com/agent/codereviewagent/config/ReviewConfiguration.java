package com.agent.codereviewagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用 diff 等待配置。 */
@Configuration
@EnableConfigurationProperties(DiffWaitProperties.class)
public class ReviewConfiguration {
}
