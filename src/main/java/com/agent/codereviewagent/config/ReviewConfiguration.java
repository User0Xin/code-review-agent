package com.agent.codereviewagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DiffWaitProperties.class)
public class ReviewConfiguration {
}
