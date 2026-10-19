package com.agent.codereviewagent.service;

import java.util.Optional;

/** 从源分支名取出工作项 ID，例如 feature/7058321625-openapi。 */
public final class WorkItemIds {

    private WorkItemIds() {
    }

    public static Optional<String> fromBranch(String sourceBranch) {
        if (sourceBranch == null || sourceBranch.isBlank()) {
            return Optional.empty();
        }
        String name = sourceBranch;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dash = name.indexOf('-');
        if (dash <= 0) {
            return Optional.empty();
        }
        String id = name.substring(0, dash);
        return id.chars().allMatch(Character::isDigit) ? Optional.of(id) : Optional.empty();
    }
}
