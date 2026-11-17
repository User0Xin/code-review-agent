package com.agent.codereviewagent.models.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 每个项目一条总结进度。lastFindingId 用于统计新发现，较早但尚未用于修订的发现仍可参与分组。 */
@Data
@TableName("revision_checkpoint")
public class RevisionCheckpointEntity {
    @TableId(value = "project_id", type = IdType.INPUT)
    private Long projectId;
    private Long lastFindingId;
    private Boolean retryPending;
    /** 以逗号分隔的发现编号；重试时用来核对原先触发总结的那批发现。 */
    private String retryFindingIds;
}
