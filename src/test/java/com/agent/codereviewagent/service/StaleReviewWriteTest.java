package com.agent.codereviewagent.service;

import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.config.GithubProperties;
import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.impl.MergeRequestReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 在写回中途切换 diff，检查后续评论与旧讨论不会继续使用过时的模型结论。 */
class StaleReviewWriteTest {
    private static final long REPO = 42, PR = 7, OLD_THREAD = 12;
    private static final RepositoryClient.DiffRefs REVIEWED = new RepositoryClient.DiffRefs("head", "base", null);
    private final RepositoryClient repo = mock(RepositoryClient.class);
    private final ReviewRecordService records = mock(ReviewRecordService.class);
    private final FindingService findings = mock(FindingService.class);
    private final Reviewer model = mock(Reviewer.class);
    private MergeRequestReviewService service;

    @BeforeEach
    void setUp() {
        var properties = new GithubProperties();
        properties.setRepositoryId(REPO);
        var standards = mock(CodingStandardSource.class);
        when(standards.load(repo, REPO)).thenReturn(new CodingStandardSource.Snapshot("main", "base", List.of()));
        var existing = new FindingEntity();
        existing.setDiscussionId(OLD_THREAD);
        when(findings.findByMergeRequest(-REPO, PR)).thenReturn(List.of(existing));
        when(repo.diffRefs(REPO, PR)).thenReturn(Optional.of(REVIEWED));
        doCallRealMethod().when(repo).resolveDiscussion(anyLong(), anyLong(), anyLong(), any(RepositoryClient.DiffRefs.class));
        service = new MergeRequestReviewServiceImpl(mock(GitlabClient.class), model, records, findings, paths -> List.of(),
                standards, mock(CodingStandardRevisionService.class), Instant::now, duration -> {},
                new DiffWaitProperties(), new GitlabProperties(), repo, properties);
    }

    @Test
    void staleLineRejectionAbortsLaterDraftsAndResolution() {
        var line = new Reviewer.CommentDraft("App.java", 2, null, "缺陷", null, "行内问题");
        var overview = new Reviewer.CommentDraft(null, null, null, "缺陷", null, "后续问题");
        when(model.review(any())).thenReturn(new Reviewer.ReviewFindings(List.of(line, overview), List.of(OLD_THREAD)));
        when(repo.createLineDiscussion(REPO, PR, "App.java", 2, null, REVIEWED, line.commentBody()))
                .thenThrow(new DiffVersionChangedException());

        review();

        verify(repo, never()).createOverviewDiscussion(REPO, PR, overview.commentBody());
        verify(repo, never()).resolveDiscussion(anyLong(), anyLong(), anyLong(), any(RepositoryClient.DiffRefs.class));
        verify(repo, never()).resolveDiscussion(anyLong(), anyLong(), anyLong());
        verify(findings, never()).recordPostedFinding(any());
        assertStaleFailure();
    }

    @Test
    void changedBaseAfterPostingAFindingPreventsResolutionWithoutDiscardingTheFinding() {
        var overview = new Reviewer.CommentDraft(null, null, null, "缺陷", null, "尚未解决的问题");
        when(model.review(any())).thenReturn(new Reviewer.ReviewFindings(List.of(overview), List.of(OLD_THREAD)));
        when(repo.createOverviewDiscussion(REPO, PR, overview.commentBody())).thenAnswer(invocation -> {
            when(repo.diffRefs(REPO, PR)).thenReturn(Optional.of(new RepositoryClient.DiffRefs("head", "new-base", null)));
            return 25L;
        });

        review();

        verify(findings).recordPostedFinding(argThat(finding -> finding.getDiscussionId() == 25L));
        verify(repo, never()).resolveDiscussion(anyLong(), anyLong(), anyLong());
        assertStaleFailure();
    }

    @Test
    void overviewAfterARecordedLineCommentIsNotPublishedAgainstANewerHead() {
        var first = new Reviewer.CommentDraft("App.java", 2, null, "缺陷", null, "第一条");
        var next = new Reviewer.CommentDraft(null, null, null, "缺陷", null, "第二条");
        when(model.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of(first, next)));
        when(repo.createLineDiscussion(REPO, PR, "App.java", 2, null, REVIEWED, first.commentBody()))
                .thenAnswer(invocation -> {
                    when(repo.diffRefs(REPO, PR)).thenReturn(Optional.of(new RepositoryClient.DiffRefs("updated", "base", null)));
                    return 25L;
                });

        review();

        verify(findings).recordPostedFinding(argThat(finding -> finding.getDiscussionId() == 25L));
        verify(repo, never()).createOverviewDiscussion(REPO, PR, next.commentBody());
        assertStaleFailure();
    }

    private void review() { service.acceptGithub(REPO, PR, "synchronize", "说明", REVIEWED.headSha()); }

    private void assertStaleFailure() {
        var failure = ArgumentCaptor.forClass(ReviewRecordEntity.class);
        verify(records).recordFailure(failure.capture(), any(Long.class));
        assertThat(failure.getValue().getReason()).isEqualTo("审查期间提交已变化");
        verify(records, never()).recordSuccess(any());
    }
}
