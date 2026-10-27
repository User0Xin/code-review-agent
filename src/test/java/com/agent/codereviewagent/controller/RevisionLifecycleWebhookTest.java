package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.FalsePositiveJudge;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.RevisionClient;
import com.agent.codereviewagent.service.RevisionSummarizer;
import com.agent.codereviewagent.service.SpecDocumentPicker;
import com.agent.codereviewagent.service.WorkItemClient;
import com.agent.codereviewagent.utils.ReviewClock;
import com.agent.codereviewagent.utils.Sleeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 webhook 验证误报、打开修订、失败恢复与发现消费之间的关系。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/revision-lifecycle-webhook-test.db",
        "review.revision.new-findings-threshold=2",
        "review.revision.minimum-group-size=1",
        "review.gitlab.project-id=9",
        "review.standards.branch=dev",
        "review.diff-wait.max-attempts=1",
        "review.diff-wait.interval=0s"
})
@AutoConfigureMockMvc
@Import(RevisionLifecycleWebhookTest.Fakes.class)
class RevisionLifecycleWebhookTest {

    private static final String PATH = "docs/coding.md";
    private static final String STANDARD = "# 编码规范\n\n旧规则\n\n其他说明\n";
    private static final FakeGitlab GITLAB = new FakeGitlab();
    private static final FakeReviewer REVIEWER = new FakeReviewer();
    private static final FakeJudge JUDGE = new FakeJudge();
    private static final FakeSummarizer SUMMARY = new FakeSummarizer();
    private static final FakeRevisions REVISIONS = new FakeRevisions();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM revision_checkpoint");
        jdbc.update("DELETE FROM coding_standard_revision");
        jdbc.update("DELETE FROM review_failure_note");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM finding");
        GITLAB.reset();
        REVIEWER.drafts = List.of();
        JUDGE.calls.clear();
        SUMMARY.reset();
        REVISIONS.reset();
    }

    @Test
    void falsePositivesAreExcludedWhileAResolvedDiscussionStillCounts() throws Exception {
        publish("误报候选");
        long falsePositiveNote = noteId("误报候选");
        GITLAB.replies.put(falsePositiveNote, List.of("需要修改。", "不需要修改，这是已有的兼容行为。"));

        publish("已修复候选");
        long fixedNote = noteId("已修复候选");
        GITLAB.resolved.add(fixedNote);
        assertThat(SUMMARY.batches).isEmpty();
        assertThat(REVISIONS.created).isEmpty();

        publish("新的有效发现");

        assertThat(SUMMARY.batches).singleElement().satisfies(batch ->
                assertThat(batch).extracting(FindingEntity::getCommentBody)
                        .containsExactly("已修复候选", "新的有效发现"));
        assertThat(falsePositive("误报候选")).isEqualTo(1);
        assertThat(falsePositive("已修复候选")).isZero();
        assertThat(revisionId("误报候选")).isNull();
        assertThat(revisionId("已修复候选")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT false_positive_reason FROM finding WHERE comment_body = ?",
                String.class, "误报候选")).contains("最终回复");
        assertNoFailureNotification();
    }

    @Test
    void anOpenRevisionAccumulatesFindingsAndAClosedRevisionNeverReturnsUsedOnes() throws Exception {
        publish("已用发现一", "已用发现二");
        Long firstRevision = revisionId("已用发现一");
        assertThat(REVISIONS.created).hasSize(1);

        publish("积累发现一");
        publish("积累发现二");

        assertThat(REVISIONS.created).hasSize(1);
        assertThat(SUMMARY.batches).hasSize(1);
        assertThat(revisionId("积累发现一")).isNull();
        assertThat(revisionId("积累发现二")).isNull();
        REVISIONS.states.put(REVISIONS.created.get(0).iid(), "closed");

        publish();

        assertThat(REVISIONS.created).hasSize(2);
        assertThat(SUMMARY.batches).hasSize(2);
        assertThat(SUMMARY.batches.get(1)).extracting(FindingEntity::getCommentBody)
                .containsExactly("积累发现一", "积累发现二");
        assertThat(revisionId("已用发现一")).isEqualTo(firstRevision);
        assertThat(revisionId("已用发现二")).isEqualTo(firstRevision);
        assertThat(revisionId("积累发现一")).isNotNull().isNotEqualTo(firstRevision);
        assertThat(jdbc.queryForObject("SELECT state FROM coding_standard_revision WHERE id = ?",
                String.class, firstRevision)).isEqualTo("CLOSED");
        assertNoFailureNotification();
    }

    @Test
    void aSummaryFailureRetriesAtTheNextReviewWithoutNewFindingsOrNotifications() throws Exception {
        SUMMARY.failuresRemaining = 1;

        publish("总结失败发现一", "总结失败发现二");

        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(REVISIONS.created).isEmpty();
        assertThat(unconsumedCount()).isEqualTo(2);
        assertNoFailureNotification();

        publish();

        assertThat(SUMMARY.calls).isEqualTo(2);
        assertThat(REVISIONS.created).hasSize(1);
        assertThat(unconsumedCount()).isZero();
        assertNoFailureNotification();
    }

    @Test
    void aSummaryRetryStillRequiresEnoughEffectiveFindingsAfterADeveloperChangesTheirMind() throws Exception {
        SUMMARY.failuresRemaining = 1;
        publish("失败后被否定的发现", "持续有效的发现");
        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(unconsumedCount()).isEqualTo(2);
        GITLAB.replies.put(noteId("失败后被否定的发现"), List.of("不需要修改，这个发现是误报。"));

        publish();

        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(REVISIONS.created).isEmpty();
        assertThat(falsePositive("失败后被否定的发现")).isEqualTo(1);
        assertThat(unconsumedCount()).isEqualTo(2);

        publish("补足门槛的有效发现");

        assertThat(SUMMARY.calls).isEqualTo(2);
        assertThat(REVISIONS.created).hasSize(1);
        assertThat(SUMMARY.batches).singleElement().satisfies(batch ->
                assertThat(batch).extracting(FindingEntity::getCommentBody)
                        .containsExactly("持续有效的发现", "补足门槛的有效发现"));
        assertThat(revisionId("失败后被否定的发现")).isNull();
        assertThat(revisionId("持续有效的发现")).isNotNull();
        assertThat(revisionId("补足门槛的有效发现")).isNotNull();
        assertThat(unconsumedCount()).isEqualTo(1);
        assertNoFailureNotification();
    }

    @Test
    void aCreationFailureKeepsFindingsUnusedAndReusesThePreparedSourceBranch() throws Exception {
        REVISIONS.failuresBeforeCreation = 1;

        publish("创建失败发现一", "创建失败发现二");

        assertThat(REVISIONS.attemptedBranches).hasSize(1);
        assertThat(REVISIONS.created).isEmpty();
        assertThat(unconsumedCount()).isEqualTo(2);
        String preparedBranch = REVISIONS.attemptedBranches.get(0);
        assertNoFailureNotification();

        publish();

        assertThat(REVISIONS.attemptedBranches).containsExactly(preparedBranch, preparedBranch);
        assertThat(REVISIONS.created).hasSize(1);
        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(unconsumedCount()).isZero();
        assertNoFailureNotification();
    }

    @Test
    void anUnusedFalsePositiveIsRejudgedWhenTheDevelopersFinalReplyChanges() throws Exception {
        publish("态度变化候选");
        long note = noteId("态度变化候选");
        GITLAB.replies.put(note, List.of("需要修改。", "不需要修改。"));

        publish("另一有效发现");

        assertThat(falsePositive("态度变化候选")).isEqualTo(1);
        assertThat(SUMMARY.calls).isZero();
        assertThat(unconsumedCount()).isEqualTo(2);
        List<String> finalReplies = List.of("需要修改。", "不需要修改。", "重新确认，需要修改。请采纳这个发现。");
        GITLAB.replies.put(note, finalReplies);

        publish();

        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(REVISIONS.created).hasSize(1);
        assertThat(falsePositive("态度变化候选")).isZero();
        assertThat(revisionId("态度变化候选")).isNotNull();
        assertThat(JUDGE.calls.stream().filter(call -> call.body().equals("态度变化候选")).toList())
                .last().satisfies(call -> assertThat(call.replies()).containsExactlyElementsOf(finalReplies));
        assertNoFailureNotification();
    }

    @Test
    void aPreparedProposalIsCancelledIfItsEvidenceBecomesFalsePositiveBeforeRemoteCreation() throws Exception {
        REVISIONS.failuresBeforeCreation = 1;
        publish("待定发现", "稳定发现");
        String cancelledBranch = REVISIONS.attemptedBranches.get(0);
        GITLAB.replies.put(noteId("待定发现"), List.of("不需要修改，这条发现已确认是误报。"));

        publish();

        assertThat(REVISIONS.created).isEmpty();
        assertThat(REVISIONS.attemptedBranches).hasSize(1);
        assertThat(unconsumedCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM coding_standard_revision WHERE source_branch = ?",
                String.class, cancelledBranch)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT merge_request_iid FROM coding_standard_revision WHERE source_branch = ?",
                Long.class, cancelledBranch)).isNull();

        publish("后来有效发现");

        assertThat(REVISIONS.created).hasSize(1);
        assertThat(REVISIONS.created.get(0).sourceBranch()).isNotEqualTo(cancelledBranch);
        assertThat(revisionId("待定发现")).isNull();
        assertThat(revisionId("稳定发现")).isNotNull();
        assertThat(revisionId("后来有效发现")).isNotNull();
        assertNoFailureNotification();
    }

    @Test
    void aRemoteRevisionCreatedBeforeATimeoutIsRecoveredBeforeRejudgingItsEvidence() throws Exception {
        REVISIONS.failAfterCreation = true;
        publish("已远端使用发现", "另一已远端使用发现");
        assertThat(REVISIONS.created).hasSize(1);
        assertThat(unconsumedCount()).isEqualTo(2);
        int judgmentsBeforeRecovery = JUDGE.calls.size();
        GITLAB.replies.put(noteId("已远端使用发现"), List.of("不需要修改。"));

        publish();

        assertThat(REVISIONS.created).hasSize(1);
        assertThat(REVISIONS.attemptedBranches).hasSize(1);
        assertThat(unconsumedCount()).isZero();
        assertThat(JUDGE.calls).hasSize(judgmentsBeforeRecovery);
        assertThat(falsePositive("已远端使用发现")).isZero();
        assertNoFailureNotification();
    }

    private void publish(String... bodies) throws Exception {
        REVIEWER.drafts = java.util.Arrays.stream(bodies)
                .map(body -> new Reviewer.CommentDraft("main.go", null, null, "缺陷", "资源管理", body))
                .toList();
        mvc.perform(post("/gitlab/webhooks/merge-requests")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"object_kind":"merge_request","project":{"id":9},
                                 "object_attributes":{"action":"update","iid":3,"oldrev":"old-sha",
                                 "source_branch":"feature/no-ticket-example","description":"实现资源管理"}}
                                """))
                .andExpect(status().isOk());
    }

    private long noteId(String body) {
        return jdbc.queryForObject("SELECT discussion_id FROM finding WHERE comment_body = ?", Long.class, body);
    }
    private Long revisionId(String body) {
        return jdbc.queryForObject("SELECT revision_id FROM finding WHERE comment_body = ?", Long.class, body);
    }
    private Integer falsePositive(String body) {
        return jdbc.queryForObject("SELECT false_positive FROM finding WHERE comment_body = ?", Integer.class, body);
    }
    private int unconsumedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NULL", Integer.class);
    }
    private void assertNoFailureNotification() {
        assertThat(GITLAB.postedBodies).noneMatch(body -> body.startsWith("审查失败"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_failure_note", Integer.class)).isZero();
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary GitlabClient gitlabClient() { return GITLAB; }
        @Bean @Primary Reviewer reviewer() { return REVIEWER; }
        @Bean @Primary WorkItemClient workItemClient() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker specDocumentPicker() { return candidates -> List.of(); }
        @Bean @Primary CodingStandardPicker codingStandardPicker() { return candidates -> List.of(PATH); }
        @Bean @Primary FalsePositiveJudge falsePositiveJudge() { return JUDGE; }
        @Bean @Primary RevisionSummarizer revisionSummarizer() { return SUMMARY; }
        @Bean @Primary RevisionClient revisionClient() { return REVISIONS; }
        @Bean @Primary ReviewClock reviewClock() { return () -> Instant.parse("2026-10-27T09:00:00Z"); }
        @Bean @Primary Sleeper sleeper() { return duration -> { }; }
    }

    static final class FakeReviewer implements Reviewer {
        List<CommentDraft> drafts = List.of();
        @Override public ReviewFindings review(ReviewRequest request) { return ReviewFindings.of(drafts); }
    }

    static final class FakeJudge implements FalsePositiveJudge {
        final List<JudgmentCall> calls = new ArrayList<>();
        @Override public Decision judge(FindingEntity finding, List<String> externalReplies) {
            calls.add(new JudgmentCall(finding.getCommentBody(), List.copyOf(externalReplies)));
            String finalReply = externalReplies.isEmpty() ? "没有否定回复" : externalReplies.getLast();
            return new Decision(finalReply.contains("不需要修改"), "最终回复：" + finalReply);
        }
    }
    record JudgmentCall(String body, List<String> replies) { }

    static final class FakeSummarizer implements RevisionSummarizer {
        int calls;
        int failuresRemaining;
        final List<List<FindingEntity>> batches = new ArrayList<>();
        void reset() { calls = 0; failuresRemaining = 0; batches.clear(); }
        @Override public Summary summarize(List<FindingEntity> findings, List<Reviewer.StandardDocument> standards) {
            calls++;
            if (failuresRemaining-- > 0) { throw new IllegalStateException("总结模型暂时失败"); }
            batches.add(List.copyOf(findings));
            return new Summary(List.of(new Replacement("资源关闭", PATH, "旧规则", "旧规则\n应及时关闭资源。",
                    findings.stream().map(FindingEntity::getId).toList(), "依据反复出现的发现补充资源关闭要求。")));
        }
    }

    static final class FakeRevisions implements RevisionClient {
        final List<String> attemptedBranches = new ArrayList<>();
        final List<CreatedRevision> created = new ArrayList<>();
        final Map<Long, String> states = new LinkedHashMap<>();
        int failuresBeforeCreation;
        boolean failAfterCreation;
        void reset() {
            attemptedBranches.clear(); created.clear(); states.clear();
            failuresBeforeCreation = 0; failAfterCreation = false;
        }
        @Override public Optional<RemoteRevision> findBySourceBranch(long projectId, String sourceBranch) {
            return created.stream().filter(value -> value.sourceBranch().equals(sourceBranch)).findFirst()
                    .map(value -> new RemoteRevision(value.iid(), states.get(value.iid())));
        }
        @Override public RemoteRevision createOrRecover(long projectId, String sourceBranch, String targetBranch,
                                                       Map<String, String> files, String title, String description) {
            attemptedBranches.add(sourceBranch);
            if (failuresBeforeCreation-- > 0) { throw new IllegalStateException("创建修订暂时失败"); }
            Optional<RemoteRevision> existing = findBySourceBranch(projectId, sourceBranch);
            if (existing.isPresent()) { return existing.get(); }
            long iid = 100L + created.size();
            created.add(new CreatedRevision(iid, sourceBranch, targetBranch, new LinkedHashMap<>(files)));
            states.put(iid, "opened");
            if (failAfterCreation) {
                failAfterCreation = false;
                throw new IllegalStateException("GitLab 已创建修订但响应超时");
            }
            return new RemoteRevision(iid, "opened");
        }
        @Override public RemoteRevision get(long projectId, long iid) { return new RemoteRevision(iid, states.get(iid)); }
    }
    record CreatedRevision(long iid, String sourceBranch, String targetBranch, Map<String, String> files) { }

    static final class FakeGitlab implements GitlabClient {
        long nextNote;
        final List<String> postedBodies = new ArrayList<>();
        final Map<Long, List<String>> replies = new HashMap<>();
        final Set<Long> resolved = new HashSet<>();
        void reset() { nextNote = 0; postedBodies.clear(); replies.clear(); resolved.clear(); }
        @Override public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
            return Optional.of(new DiffRefs("head-sha"));
        }
        @Override public String readDiff(long projectId, long mergeRequestIid, String headSha) { return "diff"; }
        @Override public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) {
            postedBodies.add(body); return ++nextNote;
        }
        @Override public long createLineDiscussion(long projectId, long mergeRequestIid, String path,
                                                   Integer newLine, Integer oldLine, String headSha, String body) {
            return createOverviewDiscussion(projectId, mergeRequestIid, body);
        }
        @Override public void deleteNote(long projectId, long mergeRequestIid, long noteId) { }
        @Override public List<String> listReplies(long projectId, long mergeRequestIid, long discussionId) {
            return replies.getOrDefault(discussionId, List.of());
        }
        @Override public boolean isDiscussionResolved(long projectId, long mergeRequestIid, long discussionId) {
            return resolved.contains(discussionId);
        }
        @Override public void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId) { resolved.add(discussionId); }
        @Override public List<String> listFiles(long projectId, String ref) { return List.of(PATH); }
        @Override public String readFile(long projectId, String path, String ref) {
            assertThat(path).isEqualTo(PATH); return STANDARD;
        }
        @Override public String defaultBranch(long projectId) { return "main"; }
    }
}
