package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.utils.ReviewClock;
import com.agent.codereviewagent.utils.Sleeper;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.config.StandardsBranchProperties;
import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.SpecDocumentPicker;
import com.agent.codereviewagent.service.WorkItemClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MergeRequestWebhookTest.Fakes.class)
@TestPropertySource(properties = {
        "review.diff-wait.max-attempts=2",
        "review.diff-wait.interval=0s"
})
class MergeRequestWebhookTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");
    private static final FakeGitlabImpl GITLAB = new FakeGitlabImpl();
    private static final RecordingReviewerImpl REVIEWER = new RecordingReviewerImpl();
    private static final FakeWorkItems WORK_ITEMS = new FakeWorkItems();
    private static final FakeSpecPicker PICKER = new FakeSpecPicker();
    private static final FakeStandardPicker STANDARDS = new FakeStandardPicker();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReviewRecordService records;

    @Autowired
    private FindingService findings;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private StandardsBranchProperties standardsBranch;

    @BeforeEach
    void reset() {
        GITLAB.reset();
        REVIEWER.calls = 0;
        REVIEWER.findings = Reviewer.ReviewFindings.none();
        REVIEWER.resolvedDiscussionIds = List.of();
        REVIEWER.lastRequest = null;
        WORK_ITEMS.reset();
        PICKER.reset();
        STANDARDS.reset();
        standardsBranch.setBranch("");
        GITLAB.clearFiles();
        jdbcTemplate.update("DELETE FROM review_failure_note");
        jdbcTemplate.update("DELETE FROM review_record");
        jdbcTemplate.update("DELETE FROM finding");
    }

    @Test
    void openingAMergeRequestStartsAReviewWhenTheDiffIsReady() throws Exception {
        GITLAB.readyWith("abc123");

        postWebhook(openPayload());

        assertThat(records.findByMergeRequest(9, 3))
                .singleElement()
                .extracting(ReviewRecordEntity::getOutcome)
                .isEqualTo("SUCCESS");
        assertThat(REVIEWER.calls).isEqualTo(1);
        assertThat(GITLAB.notes).isEmpty();
    }

    @Test
    void aNewCommitStartsAnotherReview() throws Exception {
        GITLAB.readyWith("def456");

        postWebhook(updatePayload("oldsha"));

        assertThat(records.findByMergeRequest(9, 3)).hasSize(1);
        assertThat(REVIEWER.calls).isEqualTo(1);
    }

    @Test
    void anUpdateWithoutNewCodeDoesNothing() throws Exception {
        postWebhook(updatePayload(null));

        assertThat(records.findByMergeRequest(9, 3)).isEmpty();
        assertThat(GITLAB.diffReads).isZero();
        assertThat(REVIEWER.calls).isZero();
    }

    @Test
    void aDiffThatNeverBecomesReadyLeavesOneFailureDiscussion() throws Exception {
        postWebhook(openPayload());

        assertThat(GITLAB.diffReads).isEqualTo(2);
        assertThat(REVIEWER.calls).isZero();
        assertThat(GITLAB.notes).singleElement().satisfies(note ->
                assertThat(note.body).isEqualTo("审查失败：合并请求的 diff 尚未就绪。2026-10-15T09:00:00Z"));
        assertThat(records.findByMergeRequest(9, 3))
                .singleElement()
                .extracting(ReviewRecordEntity::getOutcome)
                .isEqualTo("FAILED");
    }

    @Test
    void aLaterFailureReplacesThePreviousFailureDiscussion() throws Exception {
        postWebhook(openPayload());
        postWebhook(openPayload());

        assertThat(GITLAB.notes).singleElement().satisfies(note -> assertThat(note.id).isEqualTo(2));
        assertThat(GITLAB.deletedNoteIds).containsExactly(1L);
    }

    @Test
    void aSuccessfulReviewRemovesTheFailureDiscussion() throws Exception {
        postWebhook(openPayload());
        GITLAB.readyWith("abc123");

        postWebhook(updatePayload("oldsha"));

        assertThat(GITLAB.notes).isEmpty();
        assertThat(GITLAB.deletedNoteIds).containsExactly(1L);
        assertThat(records.findByMergeRequest(9, 3))
                .extracting(ReviewRecordEntity::getOutcome)
                .containsExactly(
                        "FAILED",
                        "SUCCESS");
    }

    @Test
    void findingsArePostedOnceAndStored() throws Exception {
        GITLAB.readyWith("abc123");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 12, null, "编码规范", "命名", "这里的命名看不出用途。"),
                new Reviewer.CommentDraft("README.md", null, null, "缺陷", null, "响应体没有关闭。")));

        postWebhook(openPayload());

        assertThat(GITLAB.notes).hasSize(2);
        assertThat(GITLAB.notes.get(0).kind).isEqualTo("line");
        assertThat(GITLAB.notes.get(0).body).isEqualTo("这里的命名看不出用途。");
        assertThat(GITLAB.notes.get(0).path).isEqualTo("main.go");
        assertThat(GITLAB.notes.get(0).newLine).isEqualTo(12);
        assertThat(GITLAB.notes.get(1).kind).isEqualTo("overview");
        assertThat(GITLAB.notes.get(1).body).isEqualTo("响应体没有关闭。");
        assertThat(findings.findByMergeRequest(9, 3)).satisfies(saved -> {
            assertThat(saved).hasSize(2);
            assertThat(saved.get(0).getDiscussionId()).isEqualTo(GITLAB.notes.get(0).id);
            assertThat(saved.get(0).getCategory()).isEqualTo("编码规范");
            assertThat(saved.get(0).getCitation()).isEqualTo("命名");
            assertThat(saved.get(0).getCommentBody()).isEqualTo("这里的命名看不出用途。");
            assertThat(saved.get(0).getHeadSha()).isEqualTo("abc123");
            assertThat(saved.get(1).getNewLine()).isNull();
            assertThat(saved.get(1).getCategory()).isEqualTo("缺陷");
        });
    }

    @Test
    void repeatedModelDraftsArePostedOnlyOnceAndOverviewFindingsStaySeparate() throws Exception {
        GITLAB.readyWith("abc123");
        var first = new Reviewer.CommentDraft(null, null, null, "缺陷", null, "响应体没有关闭。");
        var second = new Reviewer.CommentDraft(null, null, null, "缺陷", null, "锁没有释放。");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(first, first, second));

        postWebhook(openPayload());

        assertThat(GITLAB.notes).extracting(FakeGitlabImpl.Note::body)
                .containsExactly("响应体没有关闭。", "锁没有释放。");
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(2);
    }

    @Test
    void aFailedWriteKeepsTheFindingsAlreadyPosted() throws Exception {
        GITLAB.readyWith("abc123");
        GITLAB.throwOnCreateNumber = 2;
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "错误没有检查。"),
                new Reviewer.CommentDraft("main.go", 9, null, "缺陷", null, "锁没有释放。")));

        postWebhook(openPayload());

        assertThat(findings.findByMergeRequest(9, 3)).singleElement()
                .extracting(FindingEntity::getCommentBody)
                .isEqualTo("错误没有检查。");
        assertThat(GITLAB.notes).extracting(FakeGitlabImpl.Note::body)
                .containsExactly("错误没有检查。", "审查失败：有 1 条没能写回。2026-10-15T09:00:00Z");
        assertThat(records.findByMergeRequest(9, 3))
                .extracting(ReviewRecordEntity::getOutcome)
                .containsExactly("FAILED");
    }

    @Test
    void aLaterReviewKeepsTheExistingDiscussionAndShowsItToTheReviewer() throws Exception {
        GITLAB.readyWith("abc123");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "错误没有检查。")));
        postWebhook(openPayload());
        long discussionId = findings.findByMergeRequest(9, 3).get(0).getDiscussionId();
        REVIEWER.findings = Reviewer.ReviewFindings.none();

        postWebhook(updatePayload("oldsha"));

        assertThat(REVIEWER.lastRequest.priorFindings()).singleElement()
                .extracting(Reviewer.PriorFinding::discussionId, Reviewer.PriorFinding::commentBody)
                .containsExactly(discussionId, "错误没有检查。");
        assertThat(GITLAB.deletedNoteIds).doesNotContain(discussionId);
        assertThat(GITLAB.notes).extracting(FakeGitlabImpl.Note::body).containsExactly("错误没有检查。");
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
    }

    @Test
    void aFixedFindingIsResolvedWithoutDeletingTheRecord() throws Exception {
        GITLAB.readyWith("abc123");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "错误没有检查。")));
        postWebhook(openPayload());
        long discussionId = findings.findByMergeRequest(9, 3).get(0).getDiscussionId();
        REVIEWER.findings = Reviewer.ReviewFindings.none();
        REVIEWER.resolvedDiscussionIds = List.of(discussionId);

        postWebhook(updatePayload("oldsha"));

        assertThat(GITLAB.resolved).containsExactly(discussionId);
        assertThat(GITLAB.notes).extracting(FakeGitlabImpl.Note::id).contains(discussionId);
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
    }

    @Test
    void anOpenFindingIsNotPostedAgainWhenOnlyTheLineMoves() throws Exception {
        GITLAB.readyWith("abc123");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "错误没有检查。")));
        postWebhook(openPayload());
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 20, null, "缺陷", null, "错误没有检查。")));

        postWebhook(updatePayload("oldsha"));

        assertThat(GITLAB.notes).extracting(FakeGitlabImpl.Note::body).containsExactly("错误没有检查。");
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
    }

    @Test
    void aDeclinedFindingIsNotRaisedAgain() throws Exception {
        GITLAB.readyWith("abc123");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "错误没有检查。")));
        postWebhook(openPayload());
        long discussionId = findings.findByMergeRequest(9, 3).get(0).getDiscussionId();
        GITLAB.reply(discussionId, "不需要修改");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "这里还是没检查错误。")));

        postWebhook(updatePayload("oldsha"));

        assertThat(GITLAB.notes).hasSize(1);
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(1);
    }

    @Test
    void aResolvedProblemThatReturnsIsANewFinding() throws Exception {
        GITLAB.readyWith("abc123");
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "错误没有检查。")));
        postWebhook(openPayload());
        long discussionId = findings.findByMergeRequest(9, 3).get(0).getDiscussionId();
        REVIEWER.resolvedDiscussionIds = List.of(discussionId);
        REVIEWER.findings = Reviewer.ReviewFindings.of(List.of(
                new Reviewer.CommentDraft("main.go", 30, null, "缺陷", null, "错误没有检查。")));

        postWebhook(updatePayload("oldsha"));

        assertThat(GITLAB.resolved).containsExactly(discussionId);
        assertThat(findings.findByMergeRequest(9, 3)).hasSize(2);
        assertThat(GITLAB.notes).extracting(FakeGitlabImpl.Note::body)
                .containsExactly("错误没有检查。", "错误没有检查。");
    }

    @Test
    void aWorkItemAndChosenDocumentEnterTheReview() throws Exception {
        GITLAB.readyWith("abc123");
        GITLAB.file("docs/design.md", "增加导出字段");
        GITLAB.file("vendor/lib.go", "忽略");
        PICKER.chosen = java.util.List.of("docs/design.md", "vendor/lib.go", "docs/../secret.md");
        WORK_ITEMS.text = java.util.Map.of("7058321625", "工作项要求导出");

        postWebhook(payload("open", null, "feature/7058321625-export", "描述只是补充"));

        assertThat(PICKER.seen).containsExactly("docs/design.md");
        assertThat(GITLAB.readRefs).containsExactly("abc123:docs/design.md");
        assertThat(REVIEWER.lastRequest.spec().skipped()).isFalse();
        assertThat(REVIEWER.lastRequest.spec().workItemText()).isEqualTo("工作项要求导出");
        assertThat(REVIEWER.lastRequest.spec().documents()).singleElement()
                .extracting(Reviewer.SpecDocument::content)
                .isEqualTo("增加导出字段");
        assertThat(REVIEWER.lastRequest.spec().mergeRequestDescription()).isEqualTo("描述只是补充");
        assertThat(records.findByMergeRequest(9, 3).get(0).getSpecSkipped()).isFalse();
        assertThat(records.findByMergeRequest(9, 3).get(0).getSpecDocumentPaths()).isEqualTo("docs/design.md");
        assertThat(GITLAB.notes).isEmpty();
    }

    @Test
    void missingWorkItemAndDocumentsSkipSpecWithoutAComment() throws Exception {
        GITLAB.readyWith("abc123");

        postWebhook(payload("open", null, "main", "只有合并请求描述"));

        assertThat(REVIEWER.lastRequest.spec().skipped()).isTrue();
        assertThat(REVIEWER.lastRequest.spec().workItemText()).isNull();
        assertThat(REVIEWER.lastRequest.spec().documents()).isEmpty();
        assertThat(records.findByMergeRequest(9, 3).get(0).getSpecSkipped()).isTrue();
        assertThat(GITLAB.notes).isEmpty();
        assertThat(WORK_ITEMS.reads).isZero();
    }

    @Test
    void eachReviewReadsTheCurrentSpec() throws Exception {
        GITLAB.readyWith("abc123");
        WORK_ITEMS.text = java.util.Map.of("42", "第一版");
        postWebhook(payload("open", null, "feature/42-one", null));
        WORK_ITEMS.text = java.util.Map.of("42", "第二版");

        postWebhook(payload("update", "oldsha", "feature/42-one", null));

        assertThat(REVIEWER.lastRequest.spec().workItemText()).isEqualTo("第二版");
        assertThat(WORK_ITEMS.reads).isEqualTo(2);
    }

    @Test
    void standardsComeFromTheConfiguredBranchNotTheMergeRequest() throws Exception {
        GITLAB.readyWith("abc123");
        GITLAB.file("docs/coding.md", "合并请求里的旧规范");
        GITLAB.fileOn("dev", "docs/coding.md", "必须检查错误");
        GITLAB.fileOn("dev", "vendor/lib.go", "忽略");
        GITLAB.fileOn("dev", ".golangci.yml", "linters: {}");
        STANDARDS.chosen = java.util.List.of("docs/coding.md", "vendor/lib.go", ".golangci.yml");
        standardsBranch.setBranch("dev");

        mockMvc.perform(post("/gitlab/webhooks/merge-requests")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(openPayload())))
                .andExpect(status().isOk());

        assertThat(GITLAB.readRefs).contains("dev:docs/coding.md");
        assertThat(GITLAB.readRefs).doesNotContain("abc123:docs/coding.md", "dev:vendor/lib.go", "dev:.golangci.yml");
        assertThat(REVIEWER.lastRequest.codingStandards().documents()).singleElement()
                .extracting(Reviewer.StandardDocument::content)
                .isEqualTo("必须检查错误");
        assertThat(REVIEWER.lastRequest.codingStandards().smellsApply()).isTrue();
        assertThat(REVIEWER.lastRequest.codingStandards().precedence()).isEqualTo("编码规范优先");
        assertThat(records.findByMergeRequest(9, 3).get(0).getStandardDocumentPaths()).isEqualTo("docs/coding.md");
    }

    @Test
    void missingStandardsStillReviewWithSmellsAndOpenNoRevision() throws Exception {
        GITLAB.readyWith("abc123");
        GITLAB.defaultBranchName = "main";

        postWebhook(openPayload());

        assertThat(REVIEWER.lastRequest.codingStandards().documents()).isEmpty();
        assertThat(REVIEWER.lastRequest.codingStandards().smellsApply()).isTrue();
        assertThat(records.findByMergeRequest(9, 3).get(0).getStandardDocumentPaths()).isEmpty();
        assertThat(records.findByMergeRequest(9, 3).get(0).getOutcome()).isEqualTo("SUCCESS");
        assertThat(GITLAB.notes).isEmpty();
    }

    private void postWebhook(ObjectNode body) throws Exception {
        mockMvc.perform(post("/gitlab/webhooks/merge-requests")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(200));
    }

    private ObjectNode openPayload() {
        return payload("open", null);
    }

    private ObjectNode updatePayload(String oldrev) {
        return payload("update", oldrev);
    }

    private ObjectNode payload(String action, String oldrev) {
        return payload(action, oldrev, null, null);
    }

    private ObjectNode payload(String action, String oldrev, String sourceBranch, String description) {
        ObjectNode root = json.createObjectNode();
        root.put("object_kind", "merge_request");
        root.putObject("project").put("id", 9);
        ObjectNode attributes = root.putObject("object_attributes");
        attributes.put("action", action);
        attributes.put("iid", 3);
        attributes.putObject("last_commit").put("id", "abc123");
        if (oldrev != null) {
            attributes.put("oldrev", oldrev);
        }
        if (sourceBranch != null) {
            attributes.put("source_branch", sourceBranch);
        }
        if (description != null) {
            attributes.put("description", description);
        }
        return root;
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        GitlabClient gitlabClient() {
            return GITLAB;
        }

        @Bean
        @Primary
        Reviewer reviewer() {
            return REVIEWER;
        }

        @Bean
        @Primary
        ReviewClock reviewClock() {
            return () -> NOW;
        }

        @Bean
        @Primary
        Sleeper sleeper() {
            return duration -> {
            };
        }

        @Bean
        @Primary
        WorkItemClient workItemClient() {
            return WORK_ITEMS;
        }

        @Bean
        @Primary
        SpecDocumentPicker specDocumentPicker() {
            return PICKER;
        }

        @Bean
        @Primary
        CodingStandardPicker codingStandardPicker() {
            return STANDARDS;
        }
    }

    static final class FakeGitlabImpl implements GitlabClient {

        private final AtomicLong noteIds = new AtomicLong();
        private final List<Note> notes = new ArrayList<>();
        private final List<Long> deletedNoteIds = new ArrayList<>();
        private Optional<DiffRefs> diff = Optional.empty();
        private int diffReads;
        private int creates;
        private int throwOnCreateNumber;
        private final Map<Long, List<String>> replies = new HashMap<>();
        private final List<Long> resolved = new ArrayList<>();
        private final java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, java.util.Map<String, String>> filesByRef = new java.util.LinkedHashMap<>();
        private final List<String> readRefs = new ArrayList<>();
        private String defaultBranchName = "main";

        void readyWith(String headSha) {
            diff = Optional.of(new DiffRefs(headSha));
        }

        void reset() {
            notes.clear();
            deletedNoteIds.clear();
            diff = Optional.empty();
            diffReads = 0;
            creates = 0;
            throwOnCreateNumber = 0;
            replies.clear();
            resolved.clear();
            files.clear();
            filesByRef.clear();
            readRefs.clear();
            defaultBranchName = "main";
            noteIds.set(0);
        }

        void file(String path, String content) {
            files.put(path, content);
        }

        void fileOn(String ref, String path, String content) {
            filesByRef.computeIfAbsent(ref, ignored -> new java.util.LinkedHashMap<>()).put(path, content);
        }

        void clearFiles() {
            files.clear();
            filesByRef.clear();
            readRefs.clear();
        }

        void reply(long discussionId, String body) {
            replies.computeIfAbsent(discussionId, ignored -> new ArrayList<>()).add(body);
        }

        @Override
        public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
            diffReads++;
            return diff;
        }

        @Override
        public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) {
            failIfRequested();
            long id = noteIds.incrementAndGet();
            notes.add(new Note(id, "overview", null, null, null, body));
            return id;
        }

        @Override
        public long createLineDiscussion(long projectId, long mergeRequestIid, String path, Integer newLine, Integer oldLine, String headSha, String body) {
            failIfRequested();
            long id = noteIds.incrementAndGet();
            notes.add(new Note(id, "line", path, newLine, oldLine, body));
            return id;
        }

        private void failIfRequested() {
            creates++;
            if (throwOnCreateNumber == creates) {
                throw new IllegalStateException("写回失败");
            }
        }

        @Override
        public List<String> listReplies(long projectId, long mergeRequestIid, long discussionId) {
            return List.copyOf(replies.getOrDefault(discussionId, List.of()));
        }

        @Override
        public void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId) {
            resolved.add(discussionId);
        }

        @Override
        public List<String> listFiles(long projectId, String ref) {
            if (filesByRef.containsKey(ref)) {
                return new ArrayList<>(filesByRef.get(ref).keySet());
            }
            return new ArrayList<>(files.keySet());
        }

        @Override
        public String readFile(long projectId, String path, String ref) {
            readRefs.add(ref + ":" + path);
            if (filesByRef.containsKey(ref) && filesByRef.get(ref).containsKey(path)) {
                return filesByRef.get(ref).get(path);
            }
            return files.getOrDefault(path, "");
        }

        @Override
        public String defaultBranch(long projectId) {
            return defaultBranchName;
        }

        @Override
        public void deleteNote(long projectId, long mergeRequestIid, long noteId) {
            deletedNoteIds.add(noteId);
            notes.removeIf(note -> note.id == noteId);
        }

        record Note(long id, String kind, String path, Integer newLine, Integer oldLine, String body) {
        }
    }

    static final class RecordingReviewerImpl implements Reviewer {

        private int calls;
        private ReviewFindings findings = ReviewFindings.none();
        private List<Long> resolvedDiscussionIds = List.of();
        private ReviewRequest lastRequest;

        @Override
        public ReviewFindings review(ReviewRequest request) {
            calls++;
            lastRequest = request;
            return new ReviewFindings(findings.drafts(), resolvedDiscussionIds);
        }
    }

    static final class FakeWorkItems implements WorkItemClient {

        private java.util.Map<String, String> text = java.util.Map.of();
        private int reads;

        void reset() {
            text = java.util.Map.of();
            reads = 0;
        }

        @Override
        public java.util.Optional<String> readPlainText(String workItemId) {
            reads++;
            return java.util.Optional.ofNullable(text.get(workItemId));
        }
    }

    static final class FakeSpecPicker implements SpecDocumentPicker {

        private java.util.List<String> chosen = java.util.List.of();
        private java.util.List<String> seen = java.util.List.of();

        void reset() {
            chosen = java.util.List.of();
            seen = java.util.List.of();
        }

        @Override
        public java.util.List<String> pick(java.util.List<String> candidates) {
            seen = java.util.List.copyOf(candidates);
            return chosen;
        }
    }

    static final class FakeStandardPicker implements CodingStandardPicker {

        private java.util.List<String> chosen = java.util.List.of();

        void reset() {
            chosen = java.util.List.of();
        }

        @Override
        public java.util.List<String> pick(java.util.List<String> candidates) {
            return chosen;
        }
    }
}
