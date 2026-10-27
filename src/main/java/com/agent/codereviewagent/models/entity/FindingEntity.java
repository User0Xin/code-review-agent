package com.agent.codereviewagent.models.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 一条已写回评论对应的发现。 */
@Data
@TableName("finding")
public class FindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long mergeRequestIid;

    private Long discussionId;

    private String path;

    private Integer newLine;

    private Integer oldLine;

    private String category;

    private String citation;

    private String commentBody;

    private String headSha;

    private Long revisionId;
}
