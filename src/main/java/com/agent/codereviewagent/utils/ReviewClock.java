package com.agent.codereviewagent.utils;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** 审查记录和失败讨论上的时间。测试可以固定成同一个时刻。 */
public interface ReviewClock {

    Instant now();

    @Component
    class SystemReviewClockImpl implements ReviewClock {

        @Override
        public Instant now() {
            return Instant.now();
        }
    }
}
