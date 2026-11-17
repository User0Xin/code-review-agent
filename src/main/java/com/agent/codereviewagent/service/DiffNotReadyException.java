package com.agent.codereviewagent.service;

/** 平台还没有为这次合并请求提供完整的 diff 版本；稍后重试即可，不代表编号或权限有问题。 */
public final class DiffNotReadyException extends IllegalStateException {
    public DiffNotReadyException() {
        super("合并请求的 diff 尚未就绪");
    }
}
