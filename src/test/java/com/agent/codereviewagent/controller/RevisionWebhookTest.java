package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.CodingStandardPicker;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 仅从 webhook 观察编码规范修订和持久化结果，外部系统全部替换为假的。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/revision-webhook-test.db",
        "review.revision.new-findings-threshold=3",
        "review.revision.minimum-group-size=2",
        "review.standards.branch=dev",
        "review.diff-wait.max-attempts=1",
        "review.diff-wait.interval=0s"
})
@AutoConfigureMockMvc
@Import(RevisionWebhookTest.Fakes.class)
class RevisionWebhookTest {

    private static final String STANDARD_PATH = "docs/coding.md";
    private static final String STANDARD = "# 编码规范\n\n旧规则\n\n重复段落\n重复段落\n";
    private static final FakeGitlab GITLAB = new FakeGitlab();
    private static final FakeReviewer REVIEWER = new FakeReviewer();
    private static final FakeSummarizer SUMMARY = new FakeSummarizer();
    private static final FakeRevisions REVISIONS = new FakeRevisions();
    private static final AtomicLong DRAFT_IDS = new AtomicLong();

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
        SUMMARY.reset();
        REVISIONS.created.clear();
        DRAFT_IDS.set(0);
    }

    @Test
    void onlyEligibleNewFindingsCountTowardsTheConfiguredThreshold() throws Exception {
        publish("缺陷", "编码规范");
        assertThat(SUMMARY.calls).isZero();
        assertThat(REVISIONS.created).isEmpty();

        publish("spec");
        assertThat(SUMMARY.calls).isZero();
        assertThat(REVISIONS.created).isEmpty();

        publish("缺陷");

        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(SUMMARY.lastFindings).hasSize(3)
                .extracting(FindingEntity::getCategory).doesNotContain("spec");
        assertThat(REVISIONS.created).singleElement().satisfies(revision -> {
            assertThat(revision.projectId()).isEqualTo(9);
            assertThat(revision.targetBranch()).isEqualTo("dev");
            assertThat(revision.title()).contains("编码规范");
            assertThat(revision.description()).contains(STANDARD_PATH, "资源关闭");
            assertThat(revision.files()).containsExactlyEntriesOf(Map.of(
                    STANDARD_PATH, STANDARD.replace("旧规则", "旧规则\n应及时关闭资源。")));
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NOT NULL", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE category = 'spec' AND revision_id IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void groupsBelowTheMinimumRemainAvailableForAnotherSummary() throws Exception {
        SUMMARY.answer = candidates -> new RevisionSummarizer.Summary(List.of(
                replacement("资源关闭", STANDARD_PATH, "旧规则", ids(candidates, 0, 1)),
                replacement("偶发命名", STANDARD_PATH, "# 编码规范", ids(candidates, 2))));

        publish("缺陷", "缺陷", "缺陷");

        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(REVISIONS.created).singleElement().satisfies(revision ->
                assertThat(revision.files().get(STANDARD_PATH))
                        .isEqualTo(STANDARD.replace("旧规则", "旧规则\n应及时关闭资源。")));
        List<Long> consumed = jdbc.queryForList(
                "SELECT id FROM finding WHERE revision_id IS NOT NULL ORDER BY id", Long.class);
        assertThat(consumed).containsExactlyElementsOf(ids(SUMMARY.lastFindings, 0, 1));
        assertThat(jdbc.queryForObject("SELECT revision_id FROM finding WHERE id = ?", Long.class,
                SUMMARY.lastFindings.get(2).getId())).isNull();
    }

    @Test
    void onlyUniqueOriginalsInSelectedExistingStandardsAreSubmittedAndConsumed() throws Exception {
        SUMMARY.answer = candidates -> new RevisionSummarizer.Summary(List.of(
                replacement("资源关闭", STANDARD_PATH, "旧规则", ids(candidates, 0, 1)),
                replacement("缺失原文", STANDARD_PATH, "文件里没有这一段", ids(candidates, 2, 3)),
                replacement("重复原文", STANDARD_PATH, "重复段落", ids(candidates, 4, 5)),
                replacement("虚构发现", STANDARD_PATH, "# 编码规范", List.of(Long.MAX_VALUE, Long.MAX_VALUE - 1)),
                replacement("新增文件", "docs/new-standard.md", "旧规则", ids(candidates, 6, 7)),
                replacement("未选文档", "docs/unselected.md", "旧规则", ids(candidates, 8, 9))));

        publish("缺陷", "缺陷", "编码规范", "缺陷", "缺陷", "缺陷",
                "缺陷", "缺陷", "缺陷", "缺陷");

        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(REVISIONS.created).singleElement().satisfies(revision -> {
            assertThat(revision.files()).containsExactlyEntriesOf(Map.of(
                    STANDARD_PATH, STANDARD.replace("旧规则", "旧规则\n应及时关闭资源。")));
            assertThat(revision.description()).contains(STANDARD_PATH, "资源关闭");
            assertThat(revision.description()).doesNotContain("docs/new-standard.md", "docs/unselected.md");
        });
        assertThat(jdbc.queryForList("SELECT id FROM finding WHERE revision_id IS NOT NULL ORDER BY id", Long.class))
                .containsExactlyElementsOf(ids(SUMMARY.lastFindings, 0, 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NULL", Integer.class))
                .isEqualTo(8);
    }

    @Test
    void rejectedReplacementsAreRetriedAfterTheNextReviewWithoutNewFindings() throws Exception {
        SUMMARY.answer = candidates -> new RevisionSummarizer.Summary(List.of(
                replacement("资源关闭", STANDARD_PATH, "文件里没有这一段",
                        candidates.stream().map(FindingEntity::getId).toList())));

        publish("缺陷", "编码规范", "缺陷");

        assertThat(SUMMARY.calls).isEqualTo(1);
        assertThat(REVISIONS.created).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NULL", Integer.class))
                .isEqualTo(3);
        SUMMARY.answer = candidates -> new RevisionSummarizer.Summary(List.of(
                replacement("资源关闭", STANDARD_PATH, "旧规则",
                        candidates.stream().map(FindingEntity::getId).toList())));

        publish();

        assertThat(SUMMARY.calls).isEqualTo(2);
        assertThat(REVISIONS.created).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NOT NULL", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void twoNonOverlappingReplacementsInOneFileBothSurvive() throws Exception {
        SUMMARY.answer = candidates -> new RevisionSummarizer.Summary(List.of(
                replacement("资源关闭", STANDARD_PATH, "旧规则", ids(candidates, 0, 1)),
                replacement("资源关闭", STANDARD_PATH, "# 编码规范", ids(candidates, 1, 2))));

        publish("缺陷", "缺陷", "缺陷");

        assertThat(REVISIONS.created).singleElement().satisfies(revision ->
                assertThat(revision.files()).containsExactlyEntriesOf(Map.of(STANDARD_PATH,
                        STANDARD.replace("旧规则", "旧规则\n应及时关闭资源。")
                                .replace("# 编码规范", "# 编码规范\n应及时关闭资源。"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NOT NULL", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void anOverlappingReplacementCannotOverwriteAnAcceptedPatchOrConsumeItsOtherFinding() throws Exception {
        SUMMARY.answer = candidates -> new RevisionSummarizer.Summary(List.of(
                replacement("资源关闭", STANDARD_PATH, "旧规则", ids(candidates, 0, 1)),
                new RevisionSummarizer.Replacement("资源关闭", STANDARD_PATH, "# 编码规范\n\n旧规则",
                        "# 不应出现的标题\n\n重写规则", ids(candidates, 1, 2), "这个重叠片段应被拒绝。")));

        publish("缺陷", "缺陷", "缺陷");

        assertThat(REVISIONS.created).singleElement().satisfies(revision ->
                assertThat(revision.files()).containsExactlyEntriesOf(Map.of(STANDARD_PATH,
                        STANDARD.replace("旧规则", "旧规则\n应及时关闭资源。"))));
        assertThat(jdbc.queryForList("SELECT id FROM finding WHERE revision_id IS NOT NULL ORDER BY id", Long.class))
                .containsExactlyElementsOf(ids(SUMMARY.lastFindings, 0, 1));
        assertThat(jdbc.queryForObject("SELECT revision_id FROM finding WHERE id = ?", Long.class,
                SUMMARY.lastFindings.get(2).getId())).isNull();
    }

    @Test
    void noStandardsMeansNoSummaryOrRevisionEvenAfterTheThreshold() throws Exception {
        GITLAB.selectedStandards = List.of();

        publish("缺陷", "缺陷", "缺陷");

        assertThat(SUMMARY.calls).isZero();
        assertThat(REVISIONS.created).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id IS NULL", Integer.class))
                .isEqualTo(3);
    }

    private void publish(String... categories) throws Exception {
        REVIEWER.drafts = java.util.Arrays.stream(categories)
                .map(category -> new Reviewer.CommentDraft("main.go", null, null, category, "资源管理",
                        "发现 " + DRAFT_IDS.incrementAndGet() + "：这里需要检查资源是否关闭。"))
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

    private static RevisionSummarizer.Replacement replacement(String group, String path, String original,
                                                               List<Long> findingIds) {
        return new RevisionSummarizer.Replacement(group, path, original,
                original + "\n应及时关闭资源。", findingIds, "资源关闭：同类发现反复出现，需要明确关闭要求。");
    }

    private static List<Long> ids(List<FindingEntity> candidates, int... indexes) {
        return java.util.Arrays.stream(indexes).mapToObj(index -> candidates.get(index).getId()).toList();
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary GitlabClient gitlabClient() { return GITLAB; }
        @Bean @Primary Reviewer reviewer() { return REVIEWER; }
        @Bean @Primary WorkItemClient workItemClient() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker specDocumentPicker() { return candidates -> List.of(); }
        @Bean @Primary CodingStandardPicker codingStandardPicker() { return candidates -> GITLAB.selectedStandards; }
        @Bean @Primary RevisionSummarizer revisionSummarizer() { return SUMMARY; }
        @Bean @Primary RevisionClient revisionClient() { return REVISIONS; }
        @Bean @Primary ReviewClock reviewClock() { return () -> Instant.parse("2026-10-27T08:00:00Z"); }
        @Bean @Primary Sleeper sleeper() { return duration -> { }; }
    }

    static final class FakeReviewer implements Reviewer {
        List<CommentDraft> drafts = List.of();
        @Override public ReviewFindings review(ReviewRequest request) { return ReviewFindings.of(drafts); }
    }

    static final class FakeSummarizer implements RevisionSummarizer {
        int calls;
        List<FindingEntity> lastFindings = List.of();
        Function<List<FindingEntity>, Summary> answer;
        void reset() {
            calls = 0;
            lastFindings = List.of();
            answer = candidates -> new Summary(List.of(replacement("资源关闭", STANDARD_PATH, "旧规则",
                    candidates.stream().map(FindingEntity::getId).toList())));
        }
        @Override public Summary summarize(List<FindingEntity> findings, List<Reviewer.StandardDocument> standards) {
            calls++;
            lastFindings = List.copyOf(findings);
            assertThat(standards).extracting(Reviewer.StandardDocument::path).containsExactly(STANDARD_PATH);
            return answer.apply(findings);
        }
    }

    static final class FakeRevisions implements RevisionClient {
        final List<CreatedRevision> created = new ArrayList<>();
        @Override public Optional<RemoteRevision> findBySourceBranch(long projectId, String sourceBranch) {
            return created.stream().filter(value -> value.sourceBranch().equals(sourceBranch))
                    .findFirst().map(value -> new RemoteRevision(100 + created.indexOf(value), "opened"));
        }
        @Override public RemoteRevision createOrRecover(long projectId, String sourceBranch, String targetBranch,
                                                       Map<String, String> files, String title, String description) {
            created.add(new CreatedRevision(projectId, sourceBranch, targetBranch,
                    new LinkedHashMap<>(files), title, description));
            return new RemoteRevision(100 + created.size() - 1, "opened");
        }
        @Override public RemoteRevision get(long projectId, long iid) { return new RemoteRevision(iid, "closed"); }
    }

    record CreatedRevision(long projectId, String sourceBranch, String targetBranch,
                           Map<String, String> files, String title, String description) { }

    static final class FakeGitlab implements GitlabClient {
        private long nextNoteId;
        List<String> selectedStandards;
        final Map<String, String> files = new LinkedHashMap<>();
        void reset() {
            nextNoteId = 0;
            selectedStandards = List.of(STANDARD_PATH);
            files.clear();
            files.put(STANDARD_PATH, STANDARD);
            files.put("docs/unselected.md", "# 其他文档\n旧规则\n");
        }
        @Override public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
            return Optional.of(new DiffRefs("head-sha"));
        }
        @Override public String readDiff(long projectId, long mergeRequestIid, String headSha) { return "diff"; }
        @Override public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) { return ++nextNoteId; }
        @Override public long createLineDiscussion(long projectId, long mergeRequestIid, String path,
                                                   Integer newLine, Integer oldLine, String headSha, String body) {
            return ++nextNoteId;
        }
        @Override public void deleteNote(long projectId, long mergeRequestIid, long noteId) { }
        @Override public List<String> listReplies(long projectId, long mergeRequestIid, long discussionId) { return List.of(); }
        @Override public void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId) { }
        @Override public List<String> listFiles(long projectId, String ref) { return List.copyOf(files.keySet()); }
        @Override public String readFile(long projectId, String path, String ref) {
            assertThat(files).containsKey(path);
            assertThat(ref).isEqualTo("dev");
            return files.get(path);
        }
        @Override public String defaultBranch(long projectId) { return "main"; }
    }
}
