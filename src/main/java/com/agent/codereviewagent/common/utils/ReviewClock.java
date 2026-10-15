package com.agent.codereviewagent.common.utils;

import org.springframework.stereotype.Component;

import java.time.Instant;

public interface ReviewClock {

    Instant now();

    @Component
    class SystemReviewClock implements ReviewClock {

        @Override
        public Instant now() {
            return Instant.now();
        }
    }
}
