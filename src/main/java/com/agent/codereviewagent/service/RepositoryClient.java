package com.agent.codereviewagent.service;

import java.util.List;
import java.util.Optional;

/** 审查服务通过此接口读取仓库文件和合并请求的差异，并维护评论及讨论状态。 */
public interface RepositoryClient {

    Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid);

    /** 平台适配器通过受信任 API 解析仓库身份。 */
    default long resolveId(String fullName) { throw new UnsupportedOperationException("平台不支持解析仓库身份"); }

    /** 独立适配器固定仓库身份，不修改已有 webhook 适配器的全局设置。 */
    default RepositoryClient bound(long id, String fullName) { return this; }

    record Change(DiffRefs refs, String description) {}
    default Change change(long id, long number) {
        return new Change(diffRefs(id, number).orElseThrow(DiffNotReadyException::new), "");
    }

    default String readDiff(long projectId, long mergeRequestIid, String headSha) {
        return "";
    }

    default String readDiff(long projectId, long mergeRequestIid, DiffRefs refs) {
        return readDiff(projectId, mergeRequestIid, refs.headSha());
    }

    /** 与行评论一样，返回适配器可还原的正数讨论标识，供后续定位讨论或删除评论。 */
    long createOverviewDiscussion(long projectId, long mergeRequestIid, String body);

    long createLineDiscussion(long projectId, long mergeRequestIid, String path, Integer newLine, Integer oldLine, String headSha, String body);

    default long createLineDiscussion(long projectId, long mergeRequestIid, String path, Integer newLine,
                                      Integer oldLine, DiffRefs refs, String body) {
        return createLineDiscussion(projectId, mergeRequestIid, path, newLine, oldLine, refs.headSha(), body);
    }

    void deleteNote(long projectId, long mergeRequestIid, long noteId);

    /** 只返回外部回复，不含首条评论、系统消息或审查服务账号的回复。 */
    List<String> listReplies(long projectId, long mergeRequestIid, long discussionId);

    default boolean isDiscussionResolved(long projectId, long mergeRequestIid, long discussionId) {
        return false;
    }

    void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId);

    /** 如果平台需要先查找讨论，再修改状态，适配器应把核验放在实际修改之前。 */
    default void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId, DiffRefs expected) {
        expected.requireCurrent(diffRefs(projectId, mergeRequestIid));
        resolveDiscussion(projectId, mergeRequestIid, discussionId);
    }

    List<String> listFiles(long projectId, String ref);

    String readFile(long projectId, String path, String ref);

    String defaultBranch(long projectId);

    default String branchHead(long projectId, String branch) { return branch; }

    /** 完整的三个提交标识共同定位一个 diff 版本，供读取差异和定位行评论时使用。 */
    record DiffRefs(String headSha, String baseSha, String startSha) {
        public DiffRefs(String headSha) { this(headSha, null, null); }

        /** 无法确认当前仍是这一版 diff 时，不发布基于旧版本的结论。 */
        public void requireCurrent(Optional<DiffRefs> current) {
            if (current.filter(this::equals).isEmpty()) { throw new DiffVersionChangedException(); }
        }
    }
}
