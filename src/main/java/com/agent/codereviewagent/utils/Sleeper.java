package com.agent.codereviewagent.utils;

import org.springframework.stereotype.Component;

import java.time.Duration;

/** 等待 diff 的睡眠点。测试里换成不睡眠的实现，避免用例真的卡住。 */
public interface Sleeper {

    void sleep(Duration duration);

    @Component
    class ThreadSleeperImpl implements Sleeper {

        @Override
        public void sleep(Duration duration) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return;
            }
            try {
                Thread.sleep(duration);
            }
            catch (InterruptedException exception) {
                // 把中断标记还回去，再失败退出，避免吞掉中断。
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 diff 时被中断", exception);
            }
        }
    }
}
