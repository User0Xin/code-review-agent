package com.agent.codereviewagent.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SqliteReviewRecordStoreTest {

    @Autowired
    private ReviewRecordStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM review_failure_note");
        jdbcTemplate.update("DELETE FROM review_record");
    }

    @Test
    void savesAndReadsReviewRecordsInInsertionOrder() {
        store.save(new ReviewRecordStore.ReviewRecord(
                9, 3, ReviewRecordStore.ReviewRecord.Outcome.FAILED,
                "diff 未就绪", Instant.parse("2026-10-16T01:00:00Z")));
        store.save(new ReviewRecordStore.ReviewRecord(
                9, 3, ReviewRecordStore.ReviewRecord.Outcome.SUCCESS,
                null, Instant.parse("2026-10-16T01:01:00Z")));

        assertThat(store.findByMergeRequest(9, 3))
                .extracting(ReviewRecordStore.ReviewRecord::outcome)
                .containsExactly(
                        ReviewRecordStore.ReviewRecord.Outcome.FAILED,
                        ReviewRecordStore.ReviewRecord.Outcome.SUCCESS);
        assertThat(store.findByMergeRequest(9, 4)).isEmpty();
    }

    @Test
    void remembersReplacesAndForgetsTheCurrentFailureNote() {
        store.rememberFailureNote(9, 3, 101);
        store.rememberFailureNote(9, 3, 102);

        assertThat(store.failureNoteId(9, 3)).contains(102L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_failure_note WHERE project_id = 9 AND merge_request_iid = 3",
                Long.class)).isEqualTo(1L);

        store.forgetFailureNote(9, 3);

        assertThat(store.failureNoteId(9, 3)).isEmpty();
    }
}
