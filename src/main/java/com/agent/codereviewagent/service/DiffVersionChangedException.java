package com.agent.codereviewagent.service;

/** 远端已不再提供审查时的 diff；本次模型结果不能继续写回。 */
public final class DiffVersionChangedException extends IllegalStateException {
    public DiffVersionChangedException() {
        super("审查期间提交已变化");
    }
}
