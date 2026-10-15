package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.common.utils.ReviewClock;
import com.agent.codereviewagent.common.utils.Sleeper;
import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.agent.codereviewagent.service.ReviewRecordStore;
import com.agent.codereviewagent.service.Reviewer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
        if (!"Merge Request Hook".equals(gitlabEvent) || body == null || !body.path("object_kind").asText().equals("merge_request")) {
            return;
        }
        JsonNode attributes = body.path("object_attributes");
        if (!startsReview(attributes)) {
            return;
        }
        long projectId = body.path("project").path("id").asLong();
        long mergeRequestIid = attributes.path("iid").asLong();
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
        records.failureNoteId(projectId, mergeRequestIid).ifPresent(noteId -> {
            gitlabClient.deleteNote(projectId, mergeRequestIid, noteId);
            records.forgetFailureNote(projectId, mergeRequestIid);
        });
    }

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

    static String failureBody(String reason, Instant occurredAt) {
        return "审查失败：" + reason + "。" + occurredAt;
    }

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
