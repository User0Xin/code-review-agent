package com.agent.codereviewagent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** 建表脚本不会更新已存在的表；启动时检查旧表的列，只补缺失字段，保留已有审查数据。 */
@Component
@DependsOnDatabaseInitialization
public class ReviewDatabaseMigration implements InitializingBean {

    private final JdbcTemplate jdbc;

    public ReviewDatabaseMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        addColumn("review_record", "spec_skipped", "INTEGER");
        addColumn("review_record", "spec_document_paths", "TEXT");
        addColumn("review_record", "standard_document_paths", "TEXT");
        addColumn("review_record", "summary_error", "TEXT");
        addColumn("review_record", "trigger_reason", "TEXT");
        addColumn("review_record", "head_sha", "TEXT");
        addColumn("review_record", "failure_note_id", "INTEGER");
        addColumn("review_record", "note_error", "TEXT");
        addColumn("review_record", "failure_detail", "TEXT");
        addColumn("finding", "revision_id", "INTEGER");
        addColumn("finding", "false_positive", "INTEGER");
        addColumn("finding", "false_positive_reason", "TEXT");
        addColumn("coding_standard_revision", "base_ref", "TEXT");
        addColumn("revision_checkpoint", "retry_finding_ids", "TEXT");
        addColumn("workbench_job", "details_version", "INTEGER");
        addColumn("workbench_job", "failure_stage", "TEXT");
        addColumn("review_record", "workbench_job_id", "INTEGER");
        addColumn("review_record", "published_count", "INTEGER");
        addColumn("review_record", "failure_stage", "TEXT");
        addColumn("finding", "workbench_job_id", "INTEGER");
        addColumn("finding", "published_body", "TEXT");
        // 必须在补列后建索引；旧行保持 NULL，不按版本猜测历史归属。
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_review_record_job ON review_record(workbench_job_id) WHERE workbench_job_id IS NOT NULL");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_finding_job ON finding(workbench_job_id, id)");
    }

    private void addColumn(String table, String name, String type) {
        Set<String> columns = jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .map(row -> (String) row.get("name"))
                .collect(Collectors.toSet());
        if (!columns.contains(name)) {
            // 标识符均为本类常量，不接受外部输入。
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + type);
        }
    }
}
