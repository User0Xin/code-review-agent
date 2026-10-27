package com.agent.codereviewagent.models.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 已准备的精确文件修改及远端修订状态，不保存模型原始输出。 */
@Data
@TableName("coding_standard_revision")
public class CodingStandardRevisionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String sourceBranch;
    private String targetBranch;
    private String baseRef;
    private Long mergeRequestIid;
    private String state;
    private String filesJson;
    private String findingIds;
    private String title;
    private String description;
    private String error;
}
