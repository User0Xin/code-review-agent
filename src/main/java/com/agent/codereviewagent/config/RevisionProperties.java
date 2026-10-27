package com.agent.codereviewagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "review.revision")
public class RevisionProperties {
    private int newFindingsThreshold = 20;
    private int minimumGroupSize = 3;
    public int getNewFindingsThreshold() { return newFindingsThreshold; }
    public void setNewFindingsThreshold(int value) { newFindingsThreshold = Math.max(1, value); }
    public int getMinimumGroupSize() { return minimumGroupSize; }
    public void setMinimumGroupSize(int value) { minimumGroupSize = Math.max(1, value); }
}
