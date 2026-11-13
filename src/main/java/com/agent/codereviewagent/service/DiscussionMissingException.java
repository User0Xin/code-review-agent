package com.agent.codereviewagent.service;

/** 仓库和合并请求可读，但已发布的讨论已不在远端；不包括权限或网络错误。 */
public final class DiscussionMissingException extends IllegalStateException {
    public DiscussionMissingException() {
        super("已发布的讨论在远端已不存在");
    }
}
