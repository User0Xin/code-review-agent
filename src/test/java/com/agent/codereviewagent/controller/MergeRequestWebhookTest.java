package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.utils.ReviewClock;
import com.agent.codereviewagent.utils.Sleeper;
import com.agent.codereviewagent.service.GitlabClient;
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void reset() {
        GITLAB.reset();
        REVIEWER.calls = 0;
        jdbcTemplate.update("DELETE FROM review_failure_note");
        jdbcTemplate.update("DELETE FROM review_record");
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

        void readyWith(String headSha) {
            diff = Optional.of(new DiffRefs(headSha));
        }

        void reset() {
            notes.clear();
            deletedNoteIds.clear();
            diff = Optional.empty();
            diffReads = 0;
            noteIds.set(0);
        }

        @Override
        public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
            diffReads++;
            return diff;
        }

        @Override
        public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) {
            long id = noteIds.incrementAndGet();
            notes.add(new Note(id, body));
            return id;
        }

        @Override
        public void deleteNote(long projectId, long mergeRequestIid, long noteId) {
            deletedNoteIds.add(noteId);
            notes.removeIf(note -> note.id == noteId);
        }

        record Note(long id, String body) {
        }
    }

    static final class RecordingReviewerImpl implements Reviewer {

        private int calls;

        @Override
        public ReviewFindings review(ReviewRequest request) {
            calls++;
            return ReviewFindings.none();
        }
    }
}
