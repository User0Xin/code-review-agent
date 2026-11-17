package com.agent.codereviewagent.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** 一轮同步审查只读取一次配置；结束时释放绑定，下一轮在同一工作线程上也不会沿用上一个仓库的指令。 */
public record ReviewPolicy(RepositoryProfile profile, Map<String,String> instructions) {
    private static final ThreadLocal<ReviewPolicy> ACTIVE = new ThreadLocal<>();
    public static Optional<ReviewPolicy> current() { return Optional.ofNullable(ACTIVE.get()); }
    public static List<String> standardPaths() { return current().map(p -> p.profile.standardPaths()).orElse(List.of()); }
    public static List<String> specPaths() { return current().map(p -> p.profile.specPaths()).orElse(List.of()); }
    public static boolean gitlabMatches(long id) {
        return current().map(p -> p.profile.platform().equals("gitlab") && p.profile.remoteId() == id).orElse(false);
    }
    public <T> T run(Supplier<T> execution) {
        var previous = ACTIVE.get();
        ACTIVE.set(this);
        try { return execution.get(); }
        finally { if (previous == null) { ACTIVE.remove(); } else { ACTIVE.set(previous); } }
    }
}
