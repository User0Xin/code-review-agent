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

    /** 存的是 GitLab 讨论首条评论的数字 ID，用它可以再找到整条讨论。 */
    private Long discussionId;

    private String path;

    private Integer newLine;

    private Integer oldLine;

    private String category;

    private String citation;

    private String commentBody;

    private String headSha;

    /** 关联修订后，这条发现不再参与后续总结，即使修订最终未合并。 */
    private Long revisionId;
    private Boolean falsePositive;
    private String falsePositiveReason;
}
