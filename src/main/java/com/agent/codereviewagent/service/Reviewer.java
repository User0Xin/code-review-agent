package com.agent.codereviewagent.service;

import java.util.List;

/** 对一次已经就绪的 diff 做审查。 */
public interface Reviewer {

    ReviewFindings review(ReviewRequest request);

    record ReviewRequest(long projectId, long mergeRequestIid, String headSha, List<PriorFinding> priorFindings) {
    }

    /** 这次审查之前已经写回的发现，以及讨论里已有的回复。 */
    record PriorFinding(
            long discussionId,
            String path,
            Integer newLine,
            Integer oldLine,
            String category,
            String commentBody,
            List<String> replies) {
    }

    /**
     * 准备写回的一条评论。能定位到行时填 newLine 或 oldLine，否则两者都为空，写到概览讨论。
     * 写回成功之后才是发现。
     */
    record CommentDraft(
            String path,
            Integer newLine,
            Integer oldLine,
            String category,
            String citation,
            String commentBody) {
    }

    record ReviewFindings(List<CommentDraft> drafts, List<Long> resolvedDiscussionIds) {

        public static ReviewFindings none() {
            return new ReviewFindings(List.of(), List.of());
        }

        public static ReviewFindings of(List<CommentDraft> drafts) {
            return new ReviewFindings(List.copyOf(drafts), List.of());
        }
    }
}
