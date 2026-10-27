package com.agent.codereviewagent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** 在旧数据库上补充字段，保留已有审查记录与发现。 */
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
        addColumn("finding", "revision_id", "INTEGER");
        addColumn("coding_standard_revision", "base_ref", "TEXT");
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
