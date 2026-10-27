package com.agent.codereviewagent.models.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

/** SQLite 中的一次审查结果。时间按 ISO-8601 UTC 文本保存。 */
@Data
@TableName("review_record")
public class ReviewRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long mergeRequestIid;

    private String outcome;

    private String reason;

    private String occurredAt;

    private Boolean specSkipped;

    private String specDocumentPaths;

    private String standardDocumentPaths;

    private String summaryError;
    @TableField("trigger_reason")
    private String trigger;
    private String headSha;
    private Long failureNoteId;
    private String noteError;
    private String failureDetail;
}
