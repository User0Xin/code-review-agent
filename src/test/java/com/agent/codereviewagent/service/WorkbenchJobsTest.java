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
        when(review.runConfigured(11, 1, "manual", "说明", refs)).thenAnswer(invocation -> {
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
        verify(review, times(1)).runConfigured(11, 1, "manual", "说明", refs);
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
        when(review.runConfigured(11, 3, "manual", "说明", refs)).thenAnswer(call -> {
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
        when(review.runConfigured(11, 6, "webhook", "新说明", refs)).thenReturn(success);
        pending.remove().run();
        assertThat(jobs.webhook(profile, 6, "wanted").id()).isEqualTo(first.id());
        assertThat(jobs.list(11).stream().filter(job -> job.number() == 6)).singleElement()
                .satisfies(job -> assertThat(job.status()).isEqualTo("SUCCEEDED"));
        verify(review).runConfigured(11, 6, "webhook", "新说明", refs);
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
    void pendingWebhookCoalescesWithManualJobRegisteredBeforeVersionArrives() {
        var refs = new RepositoryClient.DiffRefs("head", "base", "start");
        var jobs = prepare(8, refs);
        when(repo.change(77, 8)).thenThrow(new DiffNotReadyException())
                .thenReturn(new RepositoryClient.Change(refs, "说明"));
        jobs.webhook(profile, 8, "head");
        var manual = jobs.manual(11, 8);
        pending.remove().run();
        assertThat(jobs.list(11).stream().filter(job -> job.number() == 8)).containsExactly(manual);
        verifyNoInteractions(review);
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
