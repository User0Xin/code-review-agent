package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.service.Reviewer;

import org.springframework.stereotype.Component;

/** 占位审查器：不调用模型，也不产生发现。 */
@Component
public class EmptyReviewer implements Reviewer {

    @Override
    public ReviewFindings review(ReviewRequest request) {
        return ReviewFindings.none();
    }
}
