package com.agent.codereviewagent.service;

import java.util.*;

/** 配置只描述仓库文件树内的文档，目录选择按路径段边界展开。 */
public final class ReviewDocuments {
    private ReviewDocuments() {}
    static List<String> validate(List<String> paths) {
        if (paths == null) { return List.of(); }
        if (paths.size() > 32) { throw new IllegalArgumentException("每类最多 32 个文档位置"); }
        var result = new LinkedHashSet<String>();
        for (String value : paths) {
            if (value == null) { throw new IllegalArgumentException("文档位置不能为空"); }
            String path = value.trim().replaceFirst("/$", "");
            if (path.isEmpty() || path.length() > 500 || path.contains("\\") || path.contains(":")
                    || path.chars().anyMatch(Character::isISOControl) || !safeSegments(path)) {
                throw new IllegalArgumentException("请输入仓库内的相对文档路径");
            }
            result.add(path);
        }
        return List.copyOf(result);
    }

    private static boolean safeSegments(String path) {
        for (String part : path.split("/", -1)) {
            if (Set.of("", ".", "..", ".git", "node_modules", "vendor", "dist").contains(part)) { return false; }
        }
        return true;
    }

    static boolean document(String path) {
        if (path == null || !safeSegments(path) || path.contains("\\") || path.contains(":")) { return false; }
        String name = path.toLowerCase(Locale.ROOT);
        return List.of(".md", ".mdx", ".rst", ".txt", ".adoc").stream().anyMatch(name::endsWith);
    }

    public static List<String> select(List<String> locations, List<String> files) {
        var result = new LinkedHashSet<String>();
        for (String location : locations) {
            var found = files.stream().filter(ReviewDocuments::document)
                    .filter(path -> path.equals(location) || path.startsWith(location + "/")).sorted().toList();
            if (found.isEmpty()) { throw new IllegalArgumentException("文档位置未匹配到可读文档：" + location); }
            result.addAll(found);
        }
        return List.copyOf(result);
    }
}
