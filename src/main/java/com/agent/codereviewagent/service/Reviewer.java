package com.agent.codereviewagent.service;

import java.util.List;

/** 对一次已经就绪的 diff 做审查。 */
public interface Reviewer {

    ReviewFindings review(ReviewRequest request);

    record ReviewRequest(long projectId, long mergeRequestIid, String headSha) {
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

    record ReviewFindings(List<CommentDraft> drafts) {

        public static ReviewFindings none() {
            return new ReviewFindings(List.of());
        }
    }
}
