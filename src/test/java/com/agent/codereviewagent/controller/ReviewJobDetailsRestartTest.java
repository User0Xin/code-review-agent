package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.CodeReviewAgentApplication;
import com.agent.codereviewagent.mapper.WorkbenchJobMapper;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.ReviewJobDetails;
import com.agent.codereviewagent.service.ReviewRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** 用升级前的真实 SQLite 文件启动两次，分别覆盖结果落库前和落库后的中断。 */
class ReviewJobDetailsRestartTest {
    @TempDir Path directory;
    private static final String NOW = "2026-11-19T00:00:00Z";

    @Test
    void additiveMigrationAndRestartPreserveUnknownHistoryAndBothInterruptionWindows() throws Exception {
        Path db = directory.resolve("old.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE review_record(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,merge_request_iid INTEGER NOT NULL,outcome TEXT NOT NULL,reason TEXT,occurred_at TEXT NOT NULL)");
            sql.execute("CREATE TABLE finding(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,merge_request_iid INTEGER NOT NULL,discussion_id INTEGER NOT NULL,path TEXT,new_line INTEGER,old_line INTEGER,category TEXT,citation TEXT,comment_body TEXT NOT NULL,head_sha TEXT NOT NULL)");
            sql.execute("CREATE TABLE workbench_job(id INTEGER PRIMARY KEY AUTOINCREMENT,repository_id INTEGER NOT NULL,change_number INTEGER NOT NULL,head_sha TEXT NOT NULL,base_sha TEXT NOT NULL,start_sha TEXT NOT NULL,status TEXT NOT NULL,source TEXT NOT NULL,message TEXT,created_at TEXT NOT NULL,finished_at TEXT)");
            sql.execute("CREATE TABLE repository_profile(id INTEGER PRIMARY KEY AUTOINCREMENT,platform TEXT NOT NULL,remote_id INTEGER NOT NULL,full_name TEXT NOT NULL,url TEXT NOT NULL UNIQUE,standard_paths TEXT NOT NULL,spec_paths TEXT NOT NULL,UNIQUE(platform,remote_id))");
            sql.execute("INSERT INTO repository_profile VALUES(1,'github',77,'team/repo','https://github.com/team/repo','','')");
            sql.execute("INSERT INTO workbench_job VALUES(1,1,7,'same','base','start','FAILED','manual','旧错误','" + NOW + "','" + NOW + "')");
            sql.execute("INSERT INTO review_record VALUES(1,-77,7,'SUCCESS',NULL,'" + NOW + "')");
            sql.execute("INSERT INTO finding VALUES(1,-77,7,201,NULL,NULL,NULL,'缺陷',NULL,'历史正文','same')");
        }
        long partial;
        long complete;
        try (var first = start(db)) {
            var details = first.getBean(ReviewJobDetails.class);
            assertThat(details.get(1, 1).result().availability()).isEqualTo("LEGACY_UNLINKED");
            var mapper = first.getBean(WorkbenchJobMapper.class);
            mapper.enqueue(1, 7, "same", "base", "start", "manual", NOW);
            partial = mapper.latest(1, 7, "same", "base", "start").id();
            mapper.claim(partial);
            finding(first, partial, 7, "已保存，结果前中断");
            mapper.enqueue(1, 8, "same", "base", "start", "manual", NOW);
            complete = mapper.latest(1, 8, "same", "base", "start").id();
            mapper.claim(complete);
            finding(first, complete, 8, "已保存，结果后中断");
            var record = new ReviewRecordEntity();
            record.setWorkbenchJobId(complete); record.setProjectId(-77L); record.setMergeRequestIid(8L);
            record.setPublishedCount(1); record.setSpecSkipped(true); record.setSpecDocumentPaths("");
            record.setStandardDocumentPaths("original.md"); record.setOccurredAt(NOW);
            first.getBean(ReviewRecordService.class).recordSuccess(record);
        }
        try (var second = start(db)) {
            var details = second.getBean(ReviewJobDetails.class);
            var before = details.get(1, partial);
            assertThat(before.failure().interrupted()).isTrue();
            assertThat(before.result().availability()).isEqualTo("MISSING");
            assertThat(before.result().publishedCount()).isNull();
            assertThat(before.result().findings()).extracting("commentBody").containsExactly("已保存，结果前中断");
            var after = details.get(1, complete);
            assertThat(after.failure().interrupted()).isTrue();
            assertThat(after.result().availability()).isEqualTo("RECORDED");
            assertThat(after.result().publishedCount()).isEqualTo(1);
            assertThat(after.result().specDocumentPaths()).isEmpty();
            assertThat(after.result().standardDocumentPaths()).containsExactly("original.md");
            var jdbc = second.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT published_count FROM review_record WHERE id=1", Integer.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT workbench_job_id FROM finding WHERE id=1", Long.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT comment_body FROM finding WHERE id=1", String.class)).isEqualTo("历史正文");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM review_record", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workbench_job", Integer.class)).isEqualTo(3);
            assertThat(details.get(1, 1).result().findings()).isEmpty();
        }
    }

    private static void finding(ConfigurableApplicationContext context, long job, long number, String body) {
        var finding = new FindingEntity();
        finding.setWorkbenchJobId(job); finding.setProjectId(-77L); finding.setMergeRequestIid(number);
        finding.setDiscussionId(202L); finding.setCommentBody(body); finding.setPublishedBody(body); finding.setHeadSha("same");
        context.getBean(FindingService.class).recordPostedFinding(finding);
    }

    private static ConfigurableApplicationContext start(Path db) {
        return new SpringApplicationBuilder(CodeReviewAgentApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:sqlite:" + db.toAbsolutePath(), "--spring.main.banner-mode=off");
    }
}
