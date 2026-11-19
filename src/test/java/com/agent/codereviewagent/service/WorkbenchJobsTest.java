package com.agent.codereviewagent.service;

import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.mapper.WorkbenchJobMapper;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 用 SQLite 和可控队列观察任务真实转换，外部平台与评审由替身提供。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
class WorkbenchJobsTest {
    @Autowired private WorkbenchJobMapper mapper;
    private final RepositoryProfile profile = new RepositoryProfile(11, "github", 77, "dev/alpha",
            "https://github.com/dev/alpha", List.of(), List.of(), List.of());
    private final RepositoryProfiles profiles = mock(RepositoryProfiles.class);
    private final RepositoryAccess access = mock(RepositoryAccess.class);
    private final RepositoryClient repo = mock(RepositoryClient.class);
    private final MergeRequestReviewService review = mock(MergeRequestReviewService.class);
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    private WorkbenchJobs prepare(long number, RepositoryClient.DiffRefs refs) {
        when(profiles.get(profile.id())).thenReturn(profile);
        when(access.client(profile)).thenReturn(repo);
        when(repo.change(profile.remoteId(), number)).thenReturn(new RepositoryClient.Change(refs, "说明"));
        return new WorkbenchJobs(mapper, profiles, access, review, pending::addLast, new DiffWaitProperties(), duration -> {});
    }

    @Test
    void manualAndWebhookShareOneRunButANewBaseCreatesAnotherVersion() {
        var refs = new RepositoryClient.DiffRefs("head", "base-v1", null);
        var jobs = prepare(1, refs);
        var accepted = jobs.manual(11, 1);
        assertThat(accepted.status()).isEqualTo("QUEUED");
        assertThat(jobs.webhook(profile, 1, "head").id()).isEqualTo(accepted.id());
        assertThat(jobs.manual(11, 1).id()).isEqualTo(accepted.id());
        assertThat(pending).hasSize(1);
        when(review.runConfigured(11, 1, "manual", "说明", refs, accepted.id())).thenAnswer(invocation -> {
            assertThat(jobs.list(11).getFirst().status()).isEqualTo("RUNNING");
            var actual = new ReviewRecordEntity();
            actual.setOutcome("SUCCESS");
            return actual;
        });
        pending.remove().run();
        assertThat(jobs.list(11).getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(jobs.webhook(profile, 1, "head").id()).isEqualTo(accepted.id());
        var next = new RepositoryClient.DiffRefs("head", "base-v2", null);
        when(repo.change(77, 1)).thenReturn(new RepositoryClient.Change(next, "新版本"));
        assertThat(jobs.webhook(profile, 1, "head").id()).isNotEqualTo(accepted.id());
        verify(review, times(1)).runConfigured(11, 1, "manual", "说明", refs, accepted.id());
    }

    @Test
    void interruptedJobIsNotReplayedAndAFailedReviewPublishesItsOwnReason() {
        var refs = new RepositoryClient.DiffRefs("other", "base", null);
        var jobs = prepare(2, refs);
        jobs.manual(11, 2);
        var afterRestart = new WorkbenchJobs(mapper, profiles, access, review, pending::addLast, new DiffWaitProperties(), duration -> {});
        afterRestart.afterPropertiesSet();
        assertThat(afterRestart.list(11).getFirst().message()).contains("服务中断");
        pending.remove().run();
        verifyNoInteractions(review);
        when(repo.change(77, 3)).thenReturn(new RepositoryClient.Change(refs, "说明"));
        when(review.runConfigured(eq(11L), eq(3L), eq("manual"), eq("说明"), eq(refs), anyLong())).thenAnswer(call -> {
            var actual = new ReviewRecordEntity();
            actual.setOutcome("FAILED");
            actual.setReason("spec 读取失败");
            return actual;
        });
        jobs.manual(11, 3);
        pending.remove().run();
        assertThat(jobs.list(11).getFirst()).satisfies(job -> {
            assertThat(job.status()).isEqualTo("FAILED");
            assertThat(job.message()).isEqualTo("spec 读取失败");
        });
    }

    @Test
    void fullQueueLeavesAVisibleFailureInsteadOfAStuckPendingRow() {
        prepare(4, new RepositoryClient.DiffRefs("head", "base", null));
        var jobs = new WorkbenchJobs(mapper, profiles, access, review, task -> {
            throw new TaskRejectedException("full");
        }, new DiffWaitProperties(), duration -> {});
        assertThatThrownBy(() -> jobs.manual(11, 4)).isInstanceOf(ResponseStatusException.class);
        assertThat(jobs.list(11).getFirst().message()).contains("队列已满");
        assertThatThrownBy(() -> jobs.webhook(profile, 4, "head"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        assertThat(prepare(4, new RepositoryClient.DiffRefs("head", "base", null))
                .webhook(profile, 4, "head").status()).isEqualTo("QUEUED");
    }

    @Test
    void aDiffThatIsNotReadyIsAConflictRatherThanAnUnreadableRequest() {
        prepare(5, new RepositoryClient.DiffRefs("head", "base", null));
        var jobs = new WorkbenchJobs(mapper, profiles, access, review, pending::addLast, new DiffWaitProperties(), duration -> {});
        when(repo.change(77, 5)).thenThrow(new DiffNotReadyException());
        assertThatThrownBy(() -> jobs.manual(11, 5))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(jobs.list(11)).noneMatch(job -> job.number() == 5);
    }

    @Test
    void webhookWaitsForItsOwnVersionAndRunsOnlyOnce() {
        var refs = new RepositoryClient.DiffRefs("wanted", "base", "start");
        var jobs = prepare(6, refs);
        when(repo.change(77, 6)).thenThrow(new DiffNotReadyException())
                .thenReturn(new RepositoryClient.Change(new RepositoryClient.DiffRefs("older"), "旧说明"))
                .thenThrow(new DiffNotReadyException())  // 后台第一次读取时仍未就绪
                .thenReturn(new RepositoryClient.Change(refs, "新说明"));
        var first = jobs.webhook(profile, 6, "wanted");
        assertThat(jobs.webhook(profile, 6, "wanted").id()).isEqualTo(first.id());
        assertThat(pending).hasSize(1);
        var success = new ReviewRecordEntity();
        success.setOutcome("SUCCESS");
        when(review.runConfigured(11, 6, "webhook", "新说明", refs, first.id())).thenReturn(success);
        pending.remove().run();
        assertThat(jobs.webhook(profile, 6, "wanted").id()).isEqualTo(first.id());
        assertThat(jobs.list(11).stream().filter(job -> job.number() == 6)).singleElement()
                .satisfies(job -> assertThat(job.status()).isEqualTo("SUCCEEDED"));
        verify(review).runConfigured(11, 6, "webhook", "新说明", refs, first.id());
    }

    @Test
    void webhookThatNeverMatchesLeavesAFailureWithoutReviewingAnotherHead() {
        var jobs = prepare(7, new RepositoryClient.DiffRefs("newer", "base", null));
        var waiting = jobs.webhook(profile, 7, "older");
        pending.remove().run();
        assertThat(jobs.list(11).stream().filter(job -> job.id() == waiting.id())).singleElement()
                .satisfies(job -> assertThat(job.status()).isEqualTo("FAILED"));
        verifyNoInteractions(review);
    }

    @Test
    void manualReusesPendingWebhookBeforeVersionArrives() {
        var refs = new RepositoryClient.DiffRefs("head", "base", "start");
        var jobs = prepare(8, refs);
        when(repo.change(77, 8)).thenThrow(new DiffNotReadyException())
                .thenReturn(new RepositoryClient.Change(refs, "说明"));
        var webhook = jobs.webhook(profile, 8, "head");
        var manual = jobs.manual(11, 8);
        assertThat(manual.id()).isEqualTo(webhook.id());
        var success = new ReviewRecordEntity();
        success.setOutcome("SUCCESS");
        when(review.runConfigured(11, 8, "webhook", "说明", refs, webhook.id())).thenReturn(success);
        pending.remove().run();
        assertThat(jobs.list(11).stream().filter(job -> job.number() == 8)).singleElement()
                .satisfies(job -> assertThat(job.status()).isEqualTo("SUCCEEDED"));
        verify(review, times(1)).runConfigured(11, 8, "webhook", "说明", refs, webhook.id());
    }

    @Test
    void manualRechecksAnActiveWebhookThatArrivesDuringPlatformRead() throws Exception {
        var refs = new RepositoryClient.DiffRefs("head", "base", "start");
        var jobs = prepare(10, refs);
        var reading = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(repo.change(77, 10)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                reading.countDown();
                assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return new RepositoryClient.Change(new RepositoryClient.DiffRefs("old-head", "old-base", null), "old");
            }
            return new RepositoryClient.Change(refs, "new");
        });
        var manual = java.util.concurrent.CompletableFuture.supplyAsync(() -> jobs.manual(11, 10));
        try {
            assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var webhook = jobs.webhook(profile, 10, "head");
            release.countDown();
            assertThat(manual.get(5, java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(webhook.id());
            assertThat(pending).hasSize(1);
            assertThat(jobs.manual(11, 10).id()).isEqualTo(webhook.id());
            verify(repo, times(2)).change(77, 10);
        } finally { release.countDown(); }
    }

    @Test
    void failedRetryUsesCooldownThenLatestVersionAndKeepsTheOriginal() {
        var refs = new RepositoryClient.DiffRefs("old-head", "base", null);
        var jobs = prepare(11, refs);
        var original = jobs.manual(11, 11);
        mapper.finish(original.id(), "FAILED", "模型审查失败", java.time.Instant.now().toString());
        assertThat(jobs.manual(11, 11).id()).isEqualTo(original.id());
        var next = new RepositoryClient.DiffRefs("new-head", "new-base", null);
        when(repo.change(77, 11)).thenReturn(new RepositoryClient.Change(next, "latest"));
        var retry = jobs.manual(11, 11);
        assertThat(retry.id()).isNotEqualTo(original.id());
        assertThat(retry.headSha()).isEqualTo("new-head");
        assertThat(mapper.get(11, original.id()).status()).isEqualTo("FAILED");
        assertThat(jobs.manual(11, 11).id()).isEqualTo(retry.id());
    }

    @Test
    void activeOldVersionBlocksManualButDoesNotDiscardNewVersionWebhook() {
        var oldRefs = new RepositoryClient.DiffRefs("old-head", "old-base", "old-start");
        var jobs = prepare(12, oldRefs);
        var old = jobs.manual(11, 12);
        var latestRefs = new RepositoryClient.DiffRefs("new-head", "new-base", "new-start");
        when(repo.change(77, 12)).thenReturn(new RepositoryClient.Change(latestRefs, "latest"));
        assertThat(jobs.manual(11, 12).id()).isEqualTo(old.id());
        verify(repo, times(1)).change(77, 12);
        var webhook = jobs.webhook(profile, 12, "new-head");
        assertThat(webhook.id()).isNotEqualTo(old.id());
        assertThat(webhook.headSha()).isEqualTo("new-head");
        assertThat(jobs.manual(11, 12).id()).isEqualTo(webhook.id());
        verify(repo, times(2)).change(77, 12);
        assertThat(pending).hasSize(2);
    }

    @Test
    void laterFailedManualRunDoesNotHideAnEarlierSuccessfulVersion() {
        var refs = new RepositoryClient.DiffRefs("head", "base", "start");
        var jobs = prepare(9, refs);
        // 固定历史完成时间，避免用例等待三秒防连点窗口。
        String past = "2025-01-01T00:00:00Z";
        var success = jobs.manual(11, 9);
        mapper.finish(success.id(), "SUCCEEDED", "完成", past);
        var failed = jobs.manual(11, 9);
        mapper.finish(failed.id(), "FAILED", "失败", past);
        assertThat(jobs.webhook(profile, 9, "head").id()).isEqualTo(success.id());
        assertThat(jobs.list(11).stream().filter(job -> job.number() == 9)).hasSize(2);
    }
}
