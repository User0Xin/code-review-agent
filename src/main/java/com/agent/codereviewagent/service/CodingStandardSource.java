package com.agent.codereviewagent.service;

import com.agent.codereviewagent.config.StandardsBranchProperties;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 每次从规范分支重新判定并读取已生效的说明性文档。 */
@Service
public class CodingStandardSource {
    private final GitlabClient gitlab;
    private final CodingStandardPicker picker;
    private final StandardsBranchProperties properties;

    public CodingStandardSource(GitlabClient gitlab, CodingStandardPicker picker, StandardsBranchProperties properties) {
        this.gitlab = gitlab;
        this.picker = picker;
        this.properties = properties;
    }

    public Snapshot load(long projectId) {
        return load(gitlab, projectId);
    }

    public Snapshot load(RepositoryClient repository, long projectId) {
        String configured = properties.getBranch();
        String branch = configured == null || configured.isBlank() ? repository.defaultBranch(projectId) : configured;
        // 文件清单和正文共用同一提交，避免规范分支在两次读取之间更新。
        String ref = repository.branchHead(projectId, branch);
        List<String> candidates = repository.listFiles(projectId, ref).stream().filter(CodingStandardSource::document).toList();
        List<Reviewer.StandardDocument> documents = new ArrayList<>();
        for (String path : picker.pick(candidates)) {
            if (candidates.contains(path) && documents.stream().noneMatch(item -> item.path().equals(path))) {
                documents.add(new Reviewer.StandardDocument(path, repository.readFile(projectId, path, ref)));
            }
        }
        return new Snapshot(branch, ref, List.copyOf(documents));
    }

    private static boolean document(String path) {
        if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) { return false; }
        String normalized = path.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (List.of("vendor", "node_modules", ".git", "dist").contains(segment)) { return false; }
        }
        String name = normalized.toLowerCase(Locale.ROOT);
        return List.of(".md", ".mdx", ".rst", ".txt", ".adoc").stream().anyMatch(name::endsWith);
    }

    public record Snapshot(String branch, String ref, List<Reviewer.StandardDocument> documents) {
        /** 没有仓库规范时仍检查通用坏味道；两者有冲突时以仓库规范为准。 */
        public Reviewer.CodingStandards standards() { return new Reviewer.CodingStandards(documents, true, "编码规范优先"); }
    }
}
