package com.agent.codereviewagent.mapper.sqlite;

import com.agent.codereviewagent.models.entity.ReviewFailureNoteEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 失败概览讨论索引的 MyBatis-Plus Mapper。 */
@Mapper
public interface ReviewFailureNoteMapper extends BaseMapper<ReviewFailureNoteEntity> {

    /** SQLite 原子 upsert，避免先查询再写入的竞态。 */
    @Insert("""
            INSERT INTO review_failure_note (project_id, merge_request_iid, note_id)
            VALUES (#{projectId}, #{mergeRequestIid}, #{noteId})
            ON CONFLICT(project_id, merge_request_iid)
            DO UPDATE SET note_id = excluded.note_id
            """)
    int upsert(@Param("projectId") long projectId,
               @Param("mergeRequestIid") long mergeRequestIid,
               @Param("noteId") long noteId);
}
