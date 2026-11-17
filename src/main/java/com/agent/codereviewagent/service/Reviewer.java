package com.agent.codereviewagent.service;

import java.util.List;

/** 对一次已经就绪的 diff 做审查。 */
public interface Reviewer {

    ReviewFindings review(ReviewRequest request);

    /** diff 和 spec 对应本次审查的提交；编码规范另从规范分支读取。 */
    record ReviewRequest(
            long projectId,
            long mergeRequestIid,
            String headSha,
            List<PriorFinding> priorFindings,
            Spec spec,
            CodingStandards codingStandards,
            String diff) {
    }

    /** 这次审查之前已经写回的发现，以及讨论里已有的回复。 */
    record PriorFinding(
            long discussionId,
            String path,
            Integer newLine,
            Integer oldLine,
            String category,
            String commentBody,
            List<String> replies,
            boolean resolved) {
    }

    /**
     * 准备写回的一条评论。能定位到行时填 newLine 或 oldLine，否则两者都为空，写到概览讨论。
     * 若与历史发现是同一问题，可用 priorDiscussionId 指向原讨论。写回成功之后才是发现。
     */
    record CommentDraft(
            String path,
            Integer newLine,
            Integer oldLine,
            String category,
            String citation,
            String commentBody,
            Long priorDiscussionId) {

        public CommentDraft(String path, Integer newLine, Integer oldLine, String category,
                            String citation, String commentBody) {
            this(path, newLine, oldLine, category, citation, commentBody, null);
        }
    }

    /** 除待写评论外，还区分本次变更已修复的旧讨论与开发者已表明无需修改的旧讨论。 */
    record ReviewFindings(List<CommentDraft> drafts, List<Long> resolvedDiscussionIds, List<Long> declinedDiscussionIds) {

        public ReviewFindings(List<CommentDraft> drafts, List<Long> resolvedDiscussionIds) {
            this(drafts, resolvedDiscussionIds, List.of());
        }

        public static ReviewFindings none() {
            return new ReviewFindings(List.of(), List.of());
        }

        public static ReviewFindings of(List<CommentDraft> drafts) {
            return new ReviewFindings(List.copyOf(drafts), List.of());
        }
    }

    record SpecDocument(String path, String content) {
    }

    /** spec 依据来自仓库文档；没有选中文档时跳过，合并请求描述仅作补充。 */
    record Spec(
            List<SpecDocument> documents,
            String mergeRequestDescription,
            boolean skipped) {
    }

    /** 这次审查要对照的编码规范。文档来自规范分支，不是合并请求里的版本。 */
    record StandardDocument(String path, String content) {
    }

    record CodingStandards(List<StandardDocument> documents, boolean smellsApply, String precedence) {
    }
}
