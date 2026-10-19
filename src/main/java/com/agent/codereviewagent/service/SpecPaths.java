package com.agent.codereviewagent.service;

/** 仓库文档里可以进入 spec 的路径。 */
public final class SpecPaths {

    private SpecPaths() {
    }

    public static boolean isCandidate(String path) {
        if (path == null || path.isBlank() || path.contains("..")) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("docs/")) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("vendor")
                    || segment.equals("node_modules")
                    || segment.equals(".git")
                    || segment.equals("dist")) {
                return false;
            }
        }
        return true;
    }
}
