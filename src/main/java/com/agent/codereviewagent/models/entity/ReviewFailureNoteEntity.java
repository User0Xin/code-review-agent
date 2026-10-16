package com.agent.codereviewagent.models.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 合并请求当前失败概览讨论的 SQLite 索引。 */
@Data
@TableName("review_failure_note")
public class ReviewFailureNoteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long mergeRequestIid;

    private Long noteId;
}
