package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.utils.ReviewClock;
import com.agent.codereviewagent.utils.Sleeper;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.mapper.FindingStore;
import com.agent.codereviewagent.mapper.ReviewRecordStore;
import com.agent.codereviewagent.service.Reviewer;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReviewRecordStore records;

    @Autowired
    private FindingStore findings;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void reset() {
        GITLAB.reset();
        REVIEWER.calls = 0;
        REVIEWER.findings = Reviewer.ReviewFindings.none();
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
                .extracting(ReviewRecordStore.ReviewRecord::outcome)
                .isEqualTo(ReviewRecordStore.ReviewRecord.Outcome.SUCCESS);
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
                .extracting(ReviewRecordStore.ReviewRecord::outcome)
                .isEqualTo(ReviewRecordStore.ReviewRecord.Outcome.FAILED);
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
                .extracting(ReviewRecordStore.ReviewRecord::outcome)
                .containsExactly(
                        ReviewRecordStore.ReviewRecord.Outcome.FAILED,
                        ReviewRecordStore.ReviewRecord.Outcome.SUCCESS);
    }

    @Test
    void findingsArePostedOnceAndStored() throws Exception {
        GITLAB.readyWith("abc123");
        REVIEWER.findings = new Reviewer.ReviewFindings(List.of(
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
            assertThat(saved.get(0).discussionId()).isEqualTo(GITLAB.notes.get(0).id);
            assertThat(saved.get(0).category()).isEqualTo("编码规范");
            assertThat(saved.get(0).citation()).isEqualTo("命名");
            assertThat(saved.get(0).commentBody()).isEqualTo("这里的命名看不出用途。");
            assertThat(saved.get(0).headSha()).isEqualTo("abc123");
            assertThat(saved.get(1).newLine()).isNull();
            assertThat(saved.get(1).category()).isEqualTo("缺陷");
        });
    }

    @Test
    void aFailedWriteKeepsTheFindingsAlreadyPosted() throws Exception {
        GITLAB.readyWith("abc123");
        GITLAB.throwOnCreateNumber = 2;
        REVIEWER.findings = new Reviewer.ReviewFindings(List.of(
                new Reviewer.CommentDraft("main.go", 4, null, "缺陷", null, "错误没有检查。"),
                new Reviewer.CommentDraft("main.go", 9, null, "缺陷", null, "锁没有释放。")));

        postWebhook(openPayload());

        assertThat(findings.findByMergeRequest(9, 3)).singleElement()
                .extracting(FindingStore.Finding::commentBody)
                .isEqualTo("错误没有检查。");
        assertThat(GITLAB.notes).extracting(FakeGitlabImpl.Note::body)
                .containsExactly("错误没有检查。", "审查失败：有 1 条没能写回。2026-10-15T09:00:00Z");
        assertThat(records.findByMergeRequest(9, 3))
                .extracting(ReviewRecordStore.ReviewRecord::outcome)
                .containsExactly(ReviewRecordStore.ReviewRecord.Outcome.FAILED);
    }

    private void postWebhook(ObjectNode body) throws Exception {
        mockMvc.perform(post("/gitlab/webhooks/merge-requests")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk());
    }

    private ObjectNode openPayload() {
        return payload("open", null);
    }

    private ObjectNode updatePayload(String oldrev) {
        return payload("update", oldrev);
    }

    private ObjectNode payload(String action, String oldrev) {
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
    }

    static final class FakeGitlabImpl implements GitlabClient {

        private final AtomicLong noteIds = new AtomicLong();
        private final List<Note> notes = new ArrayList<>();
        private final List<Long> deletedNoteIds = new ArrayList<>();
        private Optional<DiffRefs> diff = Optional.empty();
        private int diffReads;
        private int creates;
        private int throwOnCreateNumber;

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
            noteIds.set(0);
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

        @Override
        public ReviewFindings review(ReviewRequest request) {
            calls++;
            return findings;
        }
    }
}
