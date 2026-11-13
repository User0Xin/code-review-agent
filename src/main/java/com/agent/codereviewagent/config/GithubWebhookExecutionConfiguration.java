package com.agent.codereviewagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** GitHub 交付需快速应答；未完成的任务按接收顺序处理。 */
@Configuration(proxyBeanMethods = false)
public class GithubWebhookExecutionConfiguration {

    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor githubWebhookExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("github-review-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(32);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
