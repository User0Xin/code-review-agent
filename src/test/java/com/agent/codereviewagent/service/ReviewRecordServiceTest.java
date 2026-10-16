package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReviewRecordServiceTest {

    @Autowired
    private ReviewRecordService reviewRecordService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM review_failure_note");
        jdbcTemplate.update("DELETE FROM review_record");
    }

    @Test
    void recordsReviewResultsInInsertionOrder() {
        reviewRecordService.recordFailure(
                reviewRecord("diff 未就绪", Instant.parse("2026-10-16T01:00:00Z")),
                101);
        reviewRecordService.recordSuccess(
                reviewRecord(null, Instant.parse("2026-10-16T01:01:00Z")));

        assertThat(reviewRecordService.findByMergeRequest(9, 3))
                .extracting(ReviewRecordEntity::getOutcome)
                .containsExactly("FAILED", "SUCCESS");
        assertThat(reviewRecordService.findByMergeRequest(9, 4)).isEmpty();
    }

    @Test
    void recordsFailureAndReplacesTheCurrentFailureNote() {
        reviewRecordService.recordFailure(
                reviewRecord("第一次失败", Instant.parse("2026-10-16T01:00:00Z")),
                101);
        reviewRecordService.recordFailure(
                reviewRecord("第二次失败", Instant.parse("2026-10-16T01:01:00Z")),
                102);

        assertThat(reviewRecordService.findFailureNoteId(9, 3)).contains(102L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_failure_note WHERE project_id = 9 AND merge_request_iid = 3",
                Long.class)).isEqualTo(1L);

        reviewRecordService.forgetFailureNote(9, 3);

        assertThat(reviewRecordService.findFailureNoteId(9, 3)).isEmpty();
    }

    private static ReviewRecordEntity reviewRecord(String reason, Instant occurredAt) {
        ReviewRecordEntity record = new ReviewRecordEntity();
        record.setProjectId(9L);
        record.setMergeRequestIid(3L);
        record.setReason(reason);
        record.setOccurredAt(occurredAt.toString());
        return record;
    }
}
