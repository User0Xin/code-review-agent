package com.agent.codereviewagent.service;

import java.util.function.Supplier;

/** 版本读取/核对失败时尚未发送评论，不能据此把发布数量标成未知。 */
public final class VersionCheckFailedException extends IllegalStateException {
    private VersionCheckFailedException(RuntimeException cause) {
        super("提交状态核对失败", cause);
    }

    public static <T> T read(Supplier<T> check) {
        try { return check.get(); }
        catch (DiffVersionChangedException | VersionCheckFailedException failure) { throw failure; }
        catch (RuntimeException failure) { throw new VersionCheckFailedException(failure); }
    }

    public static void verify(RepositoryClient client, long projectId, long number, RepositoryClient.DiffRefs refs) {
        read(() -> {
            refs.requireCurrent(client.diffRefs(projectId, number));
            return null;
        });
    }
}
