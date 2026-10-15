package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.service.Reviewer;

import org.springframework.stereotype.Component;

@Component
public class EmptyReviewer implements Reviewer {

    @Override
    public ReviewFindings review(ReviewRequest request) {
        return ReviewFindings.none();
    }
}
