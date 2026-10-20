package com.agent.codereviewagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** review.standards。branch 为空时使用被审查仓库的默认分支。 */
@ConfigurationProperties(prefix = "review.standards")
public class StandardsBranchProperties {

    private String branch = "";

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }
}
