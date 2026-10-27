package com.agent.codereviewagent.models.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("revision_checkpoint")
public class RevisionCheckpointEntity {
    @TableId(value = "project_id", type = IdType.INPUT)
    private Long projectId;
    private Long lastFindingId;
    private Boolean retryPending;
}
