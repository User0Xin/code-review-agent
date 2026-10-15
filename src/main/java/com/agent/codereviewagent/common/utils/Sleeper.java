package com.agent.codereviewagent.common.utils;

import org.springframework.stereotype.Component;

import java.time.Duration;

public interface Sleeper {

    void sleep(Duration duration);

    @Component
    class ThreadSleeper implements Sleeper {

        @Override
        public void sleep(Duration duration) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return;
            }
            try {
                Thread.sleep(duration);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 diff 时被中断", exception);
            }
        }
    }
}
