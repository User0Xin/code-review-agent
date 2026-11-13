package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
import com.agent.codereviewagent.models.entity.FindingEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 两个平台使用同一个数字仓库编号与请求编号，以真实 SQLite 验证历史不会串仓。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "review.gitlab.project-id=42", "review.github.repository-id=42",
        "review.standards.branch=", "review.diff-wait.max-attempts=1"
})
class CrossPlatformReviewTest {
    private static final long REPO = 42, NUMBER = 7;
    private static final String PATH = "src/Service.java", STANDARDS = "AGENTS.md", SPEC = "docs/feature.md";
    @MockitoBean private GitlabClient gitlab;
    @MockitoBean(name = "githubClient") private RepositoryClient github;
    @MockitoBean private CodingStandardPicker standardPicker;
    @MockitoBean private SpecDocumentPicker specPicker;
    @MockitoBean private Reviewer model;
    @MockitoBean private CodingStandardRevisionService revisions;
    @Autowired private MergeRequestReviewService service;
    @Autowired private ReviewRecordService records;
    @Autowired private FindingService findings;

    @Test
    void reviewsAndWritesToTheRightPlatformEvenWhenBothUseTheSameRepositoryAndRequestNumbers() {
        var gitlabRefs = prepareRepository(gitlab, "gitlab");
        var githubRefs = prepareRepository(github, "github");
        when(standardPicker.pick(List.of(STANDARDS))).thenReturn(List.of(STANDARDS));
        when(specPicker.pick(List.of(SPEC))).thenReturn(List.of(SPEC));
        // 相同位置、类别、正文：若错误地读到上一平台的发现，本轮行评论会被去重。
        var draft = new Reviewer.CommentDraft(PATH, 3, null, "缺陷", null, "空值会导致请求失败");
        when(model.review(any())).thenReturn(Reviewer.ReviewFindings.of(List.of(draft)));
        when(gitlab.createLineDiscussion(REPO, NUMBER, PATH, 3, null, gitlabRefs, draft.commentBody())).thenReturn(101L);
        when(github.createLineDiscussion(REPO, NUMBER, PATH, 3, null, githubRefs, draft.commentBody())).thenReturn(202L);

        service.accept("Merge Request Hook", new MergeRequestWebhookRequest("merge_request",
                new MergeRequestWebhookRequest.Project(REPO),
                new MergeRequestWebhookRequest.ObjectAttributes("open", NUMBER, null, "GitLab MR",
                        new MergeRequestWebhookRequest.Commit(gitlabRefs.headSha()))));
        service.acceptGithub(REPO, NUMBER, "opened", "GitHub PR", githubRefs.headSha());

        var inputs = ArgumentCaptor.forClass(Reviewer.ReviewRequest.class);
        verify(model, times(2)).review(inputs.capture());
        assertThat(inputs.getAllValues().get(1)).satisfies(input -> {
            assertThat(input.projectId()).isEqualTo(REPO);
            assertThat(input.mergeRequestIid()).isEqualTo(NUMBER);
            assertThat(input.headSha()).isEqualTo(githubRefs.headSha());
            assertThat(input.priorFindings()).isEmpty();
            assertThat(input.diff()).isEqualTo("diff from github");
            assertThat(input.codingStandards().documents())
                    .containsExactly(new Reviewer.StandardDocument(STANDARDS, "github rules"));
            assertThat(input.spec()).isEqualTo(new Reviewer.Spec(
                    List.of(new Reviewer.SpecDocument(SPEC, "github spec")), "GitHub PR", false));
        });
        verify(github).createLineDiscussion(REPO, NUMBER, PATH, 3, null, githubRefs, draft.commentBody());
        assertThat(records.findByMergeRequest(REPO, NUMBER)).singleElement()
                .satisfies(record -> assertThat(record.getHeadSha()).isEqualTo(gitlabRefs.headSha()));
        assertThat(records.findByMergeRequest(-REPO, NUMBER)).singleElement().satisfies(record -> {
            assertThat(record.getHeadSha()).isEqualTo(githubRefs.headSha());
            assertThat(record.getOutcome()).isEqualTo("SUCCESS");
        });
        assertThat(findings.findByMergeRequest(REPO, NUMBER)).extracting(FindingEntity::getDiscussionId).containsExactly(101L);
        assertThat(findings.findByMergeRequest(-REPO, NUMBER)).extracting(FindingEntity::getDiscussionId).containsExactly(202L);
        verify(revisions).checkAfterReview(REPO, NUMBER);
        verifyNoMoreInteractions(revisions);
    }

    private static RepositoryClient.DiffRefs prepareRepository(RepositoryClient client, String platform) {
        var refs = new RepositoryClient.DiffRefs(platform + "-head", platform + "-base", platform + "-start");
        when(client.diffRefs(REPO, NUMBER)).thenReturn(Optional.of(refs));
        when(client.readDiff(REPO, NUMBER, refs)).thenReturn("diff from " + platform);
        when(client.defaultBranch(REPO)).thenReturn("main");
        when(client.branchHead(REPO, "main")).thenReturn("standards-commit");
        when(client.listFiles(REPO, "standards-commit")).thenReturn(List.of(STANDARDS));
        when(client.readFile(REPO, STANDARDS, "standards-commit")).thenReturn(platform + " rules");
        when(client.listFiles(REPO, refs.headSha())).thenReturn(List.of(SPEC));
        when(client.readFile(REPO, SPEC, refs.headSha())).thenReturn(platform + " spec");
        return refs;
    }
}
