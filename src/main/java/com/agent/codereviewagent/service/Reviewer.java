package com.agent.codereviewagent.service;

import java.util.List;

/** 对一次已经就绪的 diff 做审查。 */
public interface Reviewer {

    ReviewFindings review(ReviewRequest request);

    record ReviewRequest(long projectId, long mergeRequestIid, String headSha) {
    }

    record ReviewFindings(List<String> comments) {

        public static ReviewFindings none() {
            return new ReviewFindings(List.of());
        }
    }
}
