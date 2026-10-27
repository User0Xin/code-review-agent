package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.CodeReviewAgentApplication;
import com.agent.codereviewagent.common.ResultCode;
import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 在同一个真实 SQLite 文件上重启应用；所有审查输入仍从 webhook Controller 进入。 */
class DatabaseRestartWebhookTest {

    private static final String STANDARD_PATH = "docs/coding.md";
    private static final FakeGitlab GITLAB = new FakeGitlab();
    private static final FakeRevisions REVISIONS = new FakeRevisions();

    @TempDir Path directory;

    @Test
    void upgradingAnOldDatabaseAndRestartingPreservesReviewsFindingsAndTheOpenRevision() throws Exception {
        Path database = directory.resolve("review-records.db");
        seedOldDatabase(database);
        GITLAB.nextNote = 100;
        GITLAB.head = "first-head";
        REVISIONS.requests.clear();
        Map<String, Object> savedRevision;
        Long revisionId;

        try (ConfigurableApplicationContext first = start(database)) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            assertHistoricalRows(jdbc);
            assertThat(columnNames(jdbc, "review_record")).contains(
                    "spec_skipped", "spec_document_paths", "standard_document_paths", "summary_error",
                    "trigger_reason", "head_sha", "failure_note_id", "note_error", "failure_detail");
            assertThat(columnNames(jdbc, "finding")).contains(
                    "revision_id", "false_positive", "false_positive_reason");

            webhook(first, "open", "first-head");

            assertHistoricalRows(jdbc);
            assertThat(count(jdbc, "review_record")).isEqualTo(3);
            assertThat(count(jdbc, "finding")).isEqualTo(2);
            assertThat(count(jdbc, "coding_standard_revision")).isEqualTo(1);
            revisionId = jdbc.queryForObject("SELECT id FROM coding_standard_revision", Long.class);
            assertThat(jdbc.queryForObject("SELECT revision_id FROM finding WHERE id = 2001", Long.class))
                    .isEqualTo(revisionId);
            assertThat(jdbc.queryForObject("SELECT false_positive FROM finding WHERE id = 2001", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT state FROM coding_standard_revision", String.class)).isEqualTo("OPEN");
            savedRevision = revisionState(jdbc);
            assertThat(REVISIONS.requests).hasSize(1);
        }

        GITLAB.head = "second-head";
        try (ConfigurableApplicationContext second = start(database)) {
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
            // 先检查重启后的原样状态，再发送第二次 webhook。
            assertHistoricalRows(jdbc);
            assertThat(count(jdbc, "review_record")).isEqualTo(3);
            assertThat(count(jdbc, "finding")).isEqualTo(2);
            assertThat(revisionState(jdbc)).isEqualTo(savedRevision);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id = ?", Integer.class, revisionId))
                    .isEqualTo(2);

            webhook(second, "update", "second-head");

            assertHistoricalRows(jdbc);
            assertThat(count(jdbc, "review_record")).isEqualTo(4);
            assertThat(count(jdbc, "finding")).isEqualTo(3);
            assertThat(count(jdbc, "coding_standard_revision")).isEqualTo(1);
            assertThat(revisionState(jdbc)).isEqualTo(savedRevision);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finding WHERE revision_id = ?", Integer.class, revisionId))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT revision_id FROM finding WHERE comment_body = ?", Long.class,
                    "新增发现 second-head")).isNull();
            assertThat(jdbc.queryForObject("SELECT head_sha FROM review_record ORDER BY id DESC LIMIT 1", String.class))
                    .isEqualTo("second-head");
            assertThat(jdbc.queryForObject("SELECT trigger_reason FROM review_record ORDER BY id DESC LIMIT 1", String.class))
                    .isNotBlank();
            assertThat(REVISIONS.requests).hasSize(1);
        }
    }

    private static ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(CodeReviewAgentApplication.class, Fakes.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:sqlite:" + database.toAbsolutePath(),
                        "--spring.main.banner-mode=off",
                        "--review.gitlab.project-id=9",
                        "--review.standards.branch=dev",
                        "--review.revision.new-findings-threshold=2",
                        "--review.revision.minimum-group-size=1",
                        "--review.diff-wait.max-attempts=1",
                        "--review.diff-wait.interval=0s");
    }

    private static void webhook(ConfigurableApplicationContext context, String action, String head) throws Exception {
        String payload = """
                {"object_kind":"merge_request","project":{"id":9},
                 "object_attributes":{"action":"%s","iid":3,"oldrev":"previous-head",
                 "source_branch":"feature/no-ticket-restart","description":"重启后的审查",
                 "last_commit":{"id":"%s"}}}
                """.formatted(action, head);
        var request = context.getBean(ObjectMapper.class).readValue(payload, MergeRequestWebhookRequest.class);
        var result = context.getBean(ReviewWebhookController.class).receive("Merge Request Hook", request);
        assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS);
    }

    private static List<String> columnNames(JdbcTemplate jdbc, String table) {
        return jdbc.query("PRAGMA table_info(" + table + ")", (row, index) -> row.getString("name"));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Map<String, Object> revisionState(JdbcTemplate jdbc) {
        return jdbc.queryForMap("SELECT id, source_branch, target_branch, base_ref, merge_request_iid, state, finding_ids "
                + "FROM coding_standard_revision");
    }

    private static void assertHistoricalRows(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT outcome FROM review_record WHERE id = 1001", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM review_record WHERE id = 1001", String.class))
                .isEqualTo("2026-10-15T09:00:00Z");
        assertThat(jdbc.queryForObject("SELECT reason FROM review_record WHERE id = 1002", String.class))
                .isEqualTo("历史失败原因");
        assertThat(jdbc.queryForObject("SELECT comment_body FROM finding WHERE id = 2001", String.class))
                .isEqualTo("历史发现：错误未检查。");
        assertThat(jdbc.queryForObject("SELECT head_sha FROM finding WHERE id = 2001", String.class)).isEqualTo("historical-head");
        assertThat(jdbc.queryForObject("SELECT note_id FROM review_failure_note WHERE project_id = 9 AND merge_request_iid = 4",
                Long.class)).isEqualTo(80L);
    }

    private static void seedOldDatabase(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE review_record (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        project_id INTEGER NOT NULL,
                        merge_request_iid INTEGER NOT NULL,
                        outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED')),
                        reason TEXT,
                        occurred_at TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE finding (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        project_id INTEGER NOT NULL,
                        merge_request_iid INTEGER NOT NULL,
                        discussion_id INTEGER NOT NULL,
                        path TEXT, new_line INTEGER, old_line INTEGER,
                        category TEXT, citation TEXT, comment_body TEXT NOT NULL, head_sha TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE review_failure_note (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        project_id INTEGER NOT NULL,
                        merge_request_iid INTEGER NOT NULL,
                        note_id INTEGER NOT NULL,
                        UNIQUE(project_id, merge_request_iid))
                    """);
            statement.execute("""
                    INSERT INTO review_record(id, project_id, merge_request_iid, outcome, reason, occurred_at)
                    VALUES (1001, 9, 3, 'SUCCESS', NULL, '2026-10-15T09:00:00Z'),
                           (1002, 9, 4, 'FAILED', '历史失败原因', '2026-10-15T10:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO finding(id, project_id, merge_request_iid, discussion_id, path, new_line,
                                        category, citation, comment_body, head_sha)
                    VALUES (2001, 9, 3, 71, 'main.go', 7, '缺陷', '错误处理',
                            '历史发现：错误未检查。', 'historical-head')
                    """);
            statement.execute("INSERT INTO review_failure_note(project_id, merge_request_iid, note_id) VALUES (9, 4, 80)");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary GitlabClient gitlabClient() { return GITLAB; }
        @Bean @Primary Reviewer reviewer() {
            return request -> Reviewer.ReviewFindings.of(List.of(new Reviewer.CommentDraft(
                    "main.go", null, null, "缺陷", "资源管理", "新增发现 " + request.headSha())));
        }
        @Bean @Primary WorkItemClient workItemClient() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker specDocumentPicker() { return paths -> List.of(); }
        @Bean @Primary CodingStandardPicker codingStandardPicker() { return paths -> List.of(STANDARD_PATH); }
        @Bean @Primary FalsePositiveJudge falsePositiveJudge() {
            return (finding, replies) -> new FalsePositiveJudge.Decision(false, "外部回复未否定此发现。");
        }
        @Bean @Primary RevisionSummarizer revisionSummarizer() {
            return (findings, standards) -> new RevisionSummarizer.Summary(List.of(new RevisionSummarizer.Replacement(
                    "错误处理", STANDARD_PATH, "旧规则", "旧规则\n需要检查调用错误。",
                    findings.stream().map(FindingEntity::getId).toList(), "依据积累的发现补充错误检查要求。")));
        }
        @Bean @Primary RevisionClient revisionClient() { return REVISIONS; }
        @Bean @Primary ReviewClock reviewClock() { return () -> Instant.parse("2026-10-27T10:00:00Z"); }
        @Bean @Primary Sleeper sleeper() { return duration -> { }; }
    }

    static final class FakeRevisions implements RevisionClient {
        final Map<String, RemoteRevision> requests = new LinkedHashMap<>();
        @Override public Optional<RemoteRevision> findBySourceBranch(long projectId, String sourceBranch) {
            return Optional.ofNullable(requests.get(sourceBranch));
        }
        @Override public RemoteRevision createOrRecover(long projectId, String sourceBranch, String targetBranch,
                                                       Map<String, String> files, String title, String description) {
            assertThat(targetBranch).isEqualTo("dev");
            return requests.computeIfAbsent(sourceBranch, ignored -> new RemoteRevision(44L + requests.size(), "opened"));
        }
        @Override public RemoteRevision get(long projectId, long iid) {
            return requests.values().stream().filter(value -> value.iid() == iid).findFirst().orElseThrow();
        }
    }

    static final class FakeGitlab implements GitlabClient {
        long nextNote;
        String head;
        @Override public Optional<DiffRefs> diffRefs(long projectId, long mergeRequestIid) {
            return Optional.of(new DiffRefs(head));
        }
        @Override public String readDiff(long projectId, long mergeRequestIid, String headSha) { return "diff"; }
        @Override public long createOverviewDiscussion(long projectId, long mergeRequestIid, String body) { return ++nextNote; }
        @Override public long createLineDiscussion(long projectId, long mergeRequestIid, String path,
                                                   Integer newLine, Integer oldLine, String headSha, String body) { return ++nextNote; }
        @Override public void deleteNote(long projectId, long mergeRequestIid, long noteId) { }
        @Override public List<String> listReplies(long projectId, long mergeRequestIid, long discussionId) { return List.of(); }
        @Override public void resolveDiscussion(long projectId, long mergeRequestIid, long discussionId) { }
        @Override public List<String> listFiles(long projectId, String ref) { return List.of(STANDARD_PATH); }
        @Override public String readFile(long projectId, String path, String ref) { return "# 编码规范\n旧规则\n其他说明\n"; }
        @Override public String defaultBranch(long projectId) { return "main"; }
        @Override public String branchHead(long projectId, String branch) { return "standards-sha"; }
    }
}
