package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.mapper.WorkbenchJobMapper;
import com.agent.codereviewagent.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实编排、SQLite 与管理接口；只有远端和模型使用替身。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "review.workbench.token=detail-test"})
@AutoConfigureMockMvc
class ReviewJobDetailsTest {
    @Autowired WorkbenchJobMapper mapper;
    @Autowired RepositoryProfiles profiles;
    @Autowired MergeRequestReviewService review;
    @Autowired ReviewJobDetails details;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean RepositoryAccess access;
    @MockitoBean Reviewer reviewer;
    @MockitoBean CodingStandardSource standards;
    @MockitoBean CodingStandardRevisionService revisions;
    @MockitoBean SpecDocumentPicker specs;
    @MockitoSpyBean FindingService findingService;
    private RepositoryClient remote;
    private WorkbenchJobs jobs;
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private static final RepositoryClient.DiffRefs REFS = new RepositoryClient.DiffRefs("same-head", "base", "start");
    private static final String PAST = "2025-01-01T00:00:00Z";

    @BeforeEach
    void prepare() {
        for (String table : List.of("finding", "review_record", "review_failure_note", "workbench_job", "repository_skill", "repository_profile")) {
            jdbc.update("DELETE FROM " + table);
        }
        remote = mock(RepositoryClient.class);
        when(access.client(any())).thenReturn(remote);
        when(remote.change(anyLong(), anyLong())).thenReturn(new RepositoryClient.Change(REFS, "说明"));
        when(remote.diffRefs(anyLong(), anyLong())).thenReturn(Optional.of(REFS));
        when(standards.load(any(), anyLong())).thenReturn(new CodingStandardSource.Snapshot("main", "stable",
                List.of(new Reviewer.StandardDocument("original-rules.md", "rules"))));
        when(remote.listFiles(anyLong(), anyString())).thenReturn(List.of("docs/original-spec.md"));
        when(specs.pick(any())).thenReturn(List.of("docs/original-spec.md"));
        when(remote.readFile(anyLong(), anyString(), anyString())).thenReturn("spec");
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of()));
        var ids = new AtomicLong(200);
        when(remote.createOverviewDiscussion(anyLong(), anyLong(), anyString())).thenAnswer(i -> ids.incrementAndGet());
        doCallRealMethod().when(remote).publishLine(anyLong(), anyLong(), any(), any(), any(), any(), any());
        jobs = new WorkbenchJobs(mapper, profiles, access, review, pending::addLast, new DiffWaitProperties(), d -> {});
        repository(1, "github", 77);
        repository(2, "github", 78);
        repository(3, "gitlab", 77);
    }

    private void repository(long id, String platform, long remoteId) {
        jdbc.update("INSERT INTO repository_profile(id,platform,remote_id,full_name,url,standard_paths,spec_paths) VALUES(?,?,?,?,?,'','')",
                id, platform, remoteId, "team/repo" + id,
                "https://" + (platform.equals("github") ? "github.com" : "gitlab.example.com") + "/team/repo" + id);
    }

    private long run(long repo, long number, String body) {
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.of(body == null ? List.of() : List.of(draft(body))));
        var job = jobs.manual(repo, number);
        pending.remove().run();
        return job.id();
    }

    private static Reviewer.CommentDraft draft(String body) {
        return new Reviewer.CommentDraft(null, null, null, "缺陷", "依据", body);
    }

    @Test
    void gitlabDiscussionLinksPreserveInstancePrefixAndNestedNamespaces() throws Exception {
        String repositoryUrl = "https://gitlab.example.com/instance/parent/subgroup/repository";
        jdbc.update("UPDATE repository_profile SET url=?,full_name='parent/subgroup/repository' WHERE id=3", repositoryUrl);
        when(remote.createOverviewDiscussion(77, 16, "nested")).thenReturn(9876L);
        long job = run(3, 16, "nested");
        String changeUrl = repositoryUrl + "/-/merge_requests/16";
        mvc.perform(get("/api/repositories/3/jobs/" + job).header("Authorization", "Bearer detail-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repository.url").value(repositoryUrl))
                .andExpect(jsonPath("$.data.changeUrl").value(changeUrl))
                .andExpect(jsonPath("$.data.result.findings[0].discussionUrl").value(changeUrl + "#note_9876"));
    }

    @Test
    void exactRoundsAndPlatformsRemainIsolatedAndZeroIsDifferentFromUnknown() throws Exception {
        long first = run(1, 7, "first");
        jdbc.update("UPDATE workbench_job SET finished_at=? WHERE id=?", PAST, first);
        long second = run(1, 7, "second");
        long another = run(2, 7, "another");
        long gitlab = run(3, 7, "gitlab");
        for (var pair : List.of(new Object[]{1L, first, "first"}, new Object[]{1L, second, "second"},
                new Object[]{2L, another, "another"}, new Object[]{3L, gitlab, "gitlab"})) {
            var detail = details.get((long) pair[0], (long) pair[1]);
            assertThat(detail.result().availability()).isEqualTo("RECORDED");
            assertThat(detail.result().publishedCount()).isEqualTo(1);
            assertThat(detail.result().findings()).extracting("commentBody").containsExactly(pair[2]);
            assertThat(detail.result().standardDocumentPaths()).containsExactly("original-rules.md");
            assertThat(detail.result().specDocumentPaths()).containsExactly("docs/original-spec.md");
        }
        jdbc.update("UPDATE repository_profile SET standard_paths='changed.md',spec_paths='changed-spec.md' WHERE id=1");
        assertThat(details.get(1, first).result().standardDocumentPaths()).containsExactly("original-rules.md");
        assertThat(details.get(3, gitlab).result().findings().getFirst().discussionUrl()).contains("/-/merge_requests/7#note_");
        long empty = run(1, 8, null);
        assertThat(details.get(1, empty).result().publishedCount()).isZero();
        long queued = jobs.manual(1, 9).id();
        assertThat(details.get(1, queued).result().availability()).isEqualTo("NOT_STARTED");
        assertThat(details.get(1, queued).result().publishedCount()).isNull();
        mvc.perform(get("/api/repositories/2/jobs/" + first).header("Authorization", "Bearer detail-test"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/api/repositories/1/jobs/" + first).header("Authorization", "Bearer detail-test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.diff.baseSha").value("base"))
                .andExpect(jsonPath("$.data.result.findings[0].commentBody").value("first"));
    }

    @Test
    void partialRejectionCountsOnlySavedPublicationsAndTimeoutLeavesUnknown() {
        var rejected = new IllegalStateException("secret token");
        when(remote.createOverviewDiscussion(77, 11, "second")).thenThrow(rejected);
        when(remote.publicationRejected(rejected)).thenReturn(true);
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of(draft("first"), draft("second"))));
        var job = jobs.manual(1, 11);
        pending.remove().run();
        var partial = details.get(1, job.id());
        assertThat(partial.result().publishedCount()).isEqualTo(1);
        assertThat(partial.failure().stage()).isEqualTo("PUBLISH");
        assertThat(partial.result().findings()).hasSize(1);
        assertThat(partial.toString()).doesNotContain("secret token", "failureDetail");

        when(remote.createOverviewDiscussion(77, 12, "second")).thenThrow(new IllegalStateException("timeout secret"));
        var timeout = jobs.manual(1, 12);
        pending.remove().run();
        assertThat(details.get(1, timeout.id()).result().publishedCount()).isNull();
        assertThat(details.get(1, timeout.id()).result().findings()).hasSize(1);
    }

    @Test
    void aCrashBeforeResultPreservesSavedFindingsAndDoesNotReplayOnRecovery() {
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of(draft("first"), draft("crash"))));
        when(remote.createOverviewDiscussion(77, 13, "crash")).thenThrow(new AssertionError("simulated process death"));
        var job = jobs.manual(1, 13);
        assertThatThrownBy(() -> pending.remove().run()).isInstanceOf(AssertionError.class);
        assertThat(details.get(1, job.id()).result().availability()).isEqualTo("IN_PROGRESS");
        jobs.afterPropertiesSet();
        var recovered = details.get(1, job.id());
        assertThat(recovered.failure().stage()).isEqualTo("INTERRUPTED");
        assertThat(recovered.result().availability()).isEqualTo("MISSING");
        assertThat(recovered.result().publishedCount()).isNull();
        assertThat(recovered.result().findings()).extracting("commentBody").containsExactly("first");
        verify(reviewer, times(1)).review(any());
    }

    @Test
    void failedCompensationCannotClaimAPreciseCount() {
        doThrow(new IllegalStateException("database secret")).when(findingService)
                .recordPostedFinding(argThat(f -> "second".equals(f.getCommentBody())));
        doThrow(new IllegalStateException("delete uncertain")).when(remote).deleteNote(anyLong(), anyLong(), anyLong());
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of(draft("first"), draft("second"))));
        var job = jobs.manual(1, 14);
        pending.remove().run();
        var result = details.get(1, job.id());
        assertThat(result.failure().stage()).isEqualTo("PUBLISH");
        assertThat(result.result().publishedCount()).isNull();
        assertThat(result.result().findings()).hasSize(1);
    }

    @Test
    void legacyRowsAndCorruptAssociationsNeverLeakHistoricalOrOtherRepositoryData() throws Exception {
        long id = run(1, 15, "secret-other-repository");
        jdbc.update("UPDATE workbench_job SET details_version=NULL,message='Authorization: secret' WHERE id=?", id);
        var legacy = details.get(1, id);
        assertThat(legacy.result().availability()).isEqualTo("LEGACY_UNLINKED");
        assertThat(legacy.result().publishedCount()).isNull();
        assertThat(legacy.result().findings()).isEmpty();
        assertThat(legacy.job().message()).doesNotContain("Authorization");
        jdbc.update("UPDATE workbench_job SET details_version=1 WHERE id=?", id);
        jdbc.update("UPDATE finding SET project_id=-78 WHERE workbench_job_id=?", id);
        mvc.perform(get("/api/repositories/1/jobs/" + id).header("Authorization", "Bearer detail-test"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-other"))));
    }

    @Test
    void authenticationAndParameterErrorsAlwaysUseResult() throws Exception {
        mvc.perform(get("/api/repositories/1/jobs/abc")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        for (String path : List.of("/api/repositories/1/jobs/abc", "/api/repositories/0/jobs/1", "/api/repositories/1/jobs/-1")) {
            mvc.perform(get(path).header("Authorization", "Bearer detail-test"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(get("/api/repositories/999/jobs/1").header("Authorization", "Bearer detail-test"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.msg").value("评审任务不存在或不属于所选仓库"));
    }

    @Test
    void publicationFailureIsNotHiddenByLaterDiscussionUpdateFailure() {
        long priorJob = run(1, 23, "old");
        long discussion = findingService.findByMergeRequest(-77, 23).getFirst().getDiscussionId();
        jdbc.update("UPDATE workbench_job SET finished_at=? WHERE id=?", PAST, priorJob);
        var rejected = new IllegalStateException("rejected publication");
        when(remote.createOverviewDiscussion(77, 23, "new")).thenThrow(rejected);
        when(remote.publicationRejected(rejected)).thenReturn(true);
        doThrow(new IllegalStateException("update failed")).when(remote).resolveDiscussion(77, 23, discussion, REFS);
        when(reviewer.review(any())).thenReturn(new Reviewer.ReviewFindings(List.of(draft("new")), List.of(discussion)));
        var job = jobs.manual(1, 23);
        pending.remove().run();
        assertThat(details.get(1, job.id()).failure().stage()).isEqualTo("PUBLISH");
    }

    @Test
    void versionReadFailureBeforeSendingDoesNotMakePublicationCountUnknown() {
        when(remote.diffRefs(77, 24)).thenReturn(Optional.of(REFS), Optional.of(REFS))
                .thenThrow(new IllegalStateException("version read timeout"));
        long job = run(1, 24, "not sent");
        verify(remote, never()).createOverviewDiscussion(77, 24, "not sent");
        assertThat(details.get(1, job).result().publishedCount()).isEqualTo(0);
        assertThat(details.get(1, job).failure().stage()).isEqualTo("VERSION_CHECK");
    }

    @Test
    void fractionalManualNumberIsRejectedBeforeAnyPlatformRequest() throws Exception {
        when(remote.change(77, 42)).thenThrow(new IllegalStateException("must not reach platform"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/repositories/1/jobs")
                        .header("Authorization", "Bearer detail-test").contentType("application/json")
                        .content("{\"number\":42.5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verify(remote, never()).change(anyLong(), anyLong());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preflightFailurePreservesEarlierConfirmedCountForBothCommentKinds(boolean line) {
        when(remote.diffRefs(77, 25)).thenReturn(Optional.of(REFS), Optional.of(REFS), Optional.of(REFS))
                .thenThrow(new IllegalStateException("version read failed"));
        var next = new Reviewer.CommentDraft(line ? "App.java" : null, line ? 2 : null, null, "缺陷", null, "not sent");
        when(reviewer.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of(draft("first"), next)));
        var job = jobs.manual(1, 25);
        pending.remove().run();
        verify(remote, never()).createOverviewDiscussion(77, 25, "not sent");
        verify(remote, never()).publishLine(anyLong(), anyLong(), any(), any(), any(), any(), any());
        var detail = details.get(1, job.id());
        assertThat(detail.result().publishedCount()).isEqualTo(1);
        assertThat(detail.result().findings()).extracting("commentBody").containsExactly("first");
        assertThat(detail.failure().stage()).isEqualTo("VERSION_CHECK");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"42.0", "4.2e1", "\"42\"", "true", "9223372036854775808", "0", "-1"})
    void manualNumberRejectsCoercionOverflowAndNonPositiveValues(String value) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/repositories/1/jobs")
                        .header("Authorization", "Bearer detail-test").contentType("application/json")
                        .content("{\"number\":" + value + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verify(remote, never()).change(anyLong(), anyLong());
    }
}
