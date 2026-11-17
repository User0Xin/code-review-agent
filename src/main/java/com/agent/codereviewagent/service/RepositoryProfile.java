package com.agent.codereviewagent.service;

import java.util.List;

/** 工作台本地编号与平台原生仓库编号独立；旧审查记录沿用其平台命名空间。 */
public record RepositoryProfile(long id, String platform, long remoteId, String fullName, String url,
                                List<String> standardPaths, List<String> specPaths, List<SkillStatus> skills) {
    public record SkillStatus(String slot, boolean custom, boolean enabled) {}
    public record Skill(String slot, boolean custom, boolean enabled, String content) {}
    public long storageId() { return platform.equals("github") ? -remoteId : remoteId; }
}
