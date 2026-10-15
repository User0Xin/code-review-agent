package com.agent.codereviewagent.service;

import java.util.List;

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
