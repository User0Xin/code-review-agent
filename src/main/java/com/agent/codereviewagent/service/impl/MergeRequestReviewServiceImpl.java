package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.utils.ReviewClock;
import com.agent.codereviewagent.utils.Sleeper;
import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.agent.codereviewagent.mapper.ReviewRecordStore;
import com.agent.codereviewagent.service.Reviewer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 决定一条合并请求 webhook 要不要审查。
 * diff 就绪且没有发现时记成功，并删掉之前的失败概览讨论；超过等待次数则只留一条失败讨论。
 */
@Service
public class MergeRequestReviewServiceImpl implements MergeRequestReviewService {

    static final String DIFF_NOT_READY = "合并请求的 diff 尚未就绪";

    private final GitlabClient gitlabClient;
    private final Reviewer reviewer;
    private final ReviewRecordStore records;
    private final ReviewClock clock;
    private final Sleeper sleeper;
    private final DiffWaitProperties diffWait;

    public MergeRequestReviewServiceImpl(GitlabClient gitlabClient,
                                         Reviewer reviewer,
                                         ReviewRecordStore records,
                                         ReviewClock clock,
                                         Sleeper sleeper,
                                         DiffWaitProperties diffWait) {
        this.gitlabClient = gitlabClient;
        this.reviewer = reviewer;
        this.records = records;
        this.clock = clock;
        this.sleeper = sleeper;
        this.diffWait = diffWait;
    }

    @Override
    public void accept(String gitlabEvent, JsonNode body) {
        // 只处理 GitLab 的合并请求事件。别的 hook、空 body 直接忽略，也不留审查记录。
        if (!"Merge Request Hook".equals(gitlabEvent) || body == null || !body.path("object_kind").asText().equals("merge_request")) {
            return;
        }
        JsonNode attributes = body.path("object_attributes");
        if (!startsReview(attributes)) {
            return;
        }
        long projectId = body.path("project").path("id").asLong();
        long mergeRequestIid = attributes.path("iid").asLong();
        // 缺少项目或合并请求编号时无法写回，也不记审查记录。
        if (projectId == 0 || mergeRequestIid == 0) {
            return;
        }
        GitlabClient.DiffRefs diffRefs = waitForDiff(projectId, mergeRequestIid);
        if (diffRefs == null) {
            fail(projectId, mergeRequestIid, DIFF_NOT_READY);
            return;
        }
        reviewer.review(new Reviewer.ReviewRequest(projectId, mergeRequestIid, diffRefs.headSha()));
        records.save(new ReviewRecordStore.ReviewRecord(
                projectId,
                mergeRequestIid,
                ReviewRecordStore.ReviewRecord.Outcome.SUCCESS,
                null,
                clock.now()));
        // 这次没有发现。若之前留过失败讨论，删掉它，避免成功后还挂着失败。
        records.failureNoteId(projectId, mergeRequestIid).ifPresent(noteId -> {
            gitlabClient.deleteNote(projectId, mergeRequestIid, noteId);
            records.forgetFailureNote(projectId, mergeRequestIid);
        });
    }

    /** 立刻读一次 diff。没就绪再按间隔重试，总次数至少为 1。始终没有则返回 null。 */
    private GitlabClient.DiffRefs waitForDiff(long projectId, long mergeRequestIid) {
        int attempts = Math.max(diffWait.getMaxAttempts(), 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleeper.sleep(diffWait.getInterval());
            }
            var ready = gitlabClient.diffRefs(projectId, mergeRequestIid);
            if (ready.isPresent()) {
                return ready.get();
            }
        }
        return null;
    }

    /** 同一合并请求只留最新一条失败概览讨论：先删旧的，再写新的，并记住新 note id。 */
    private void fail(long projectId, long mergeRequestIid, String reason) {
        records.failureNoteId(projectId, mergeRequestIid).ifPresent(noteId ->
                gitlabClient.deleteNote(projectId, mergeRequestIid, noteId));
        Instant occurredAt = clock.now();
        long noteId = gitlabClient.createOverviewDiscussion(
                projectId,
                mergeRequestIid,
                failureBody(reason, occurredAt));
        records.rememberFailureNote(projectId, mergeRequestIid, noteId);
        records.save(new ReviewRecordStore.ReviewRecord(
                projectId,
                mergeRequestIid,
                ReviewRecordStore.ReviewRecord.Outcome.FAILED,
                reason,
                occurredAt));
    }

    /** 失败讨论正文只有原因和时间。 */
    static String failureBody(String reason, Instant occurredAt) {
        return "审查失败：" + reason + "。" + occurredAt;
    }

    /**
     * 打开合并请求，或更新里带了 oldrev（源分支有新提交）时才审查。
     * 改标题、指派人这类不带新代码的更新不审查。
     */
    private static boolean startsReview(JsonNode attributes) {
        String action = attributes.path("action").asText();
        if ("open".equals(action)) {
            return true;
        }
        if (!"update".equals(action)) {
            return false;
        }
        JsonNode oldrev = attributes.get("oldrev");
        return oldrev != null && !oldrev.asText().isBlank();
    }
}
