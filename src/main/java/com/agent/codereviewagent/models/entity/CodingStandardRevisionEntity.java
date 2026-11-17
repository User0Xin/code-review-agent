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
    /** 读取规范时固定的提交。重试创建修订时仍基于这一版文件。 */
    private String baseRef;
    private Long mergeRequestIid;
    private String state;
    /** 键是规范文件路径，值是替换后的完整正文；中断后可据此恢复远端提交。 */
    private String filesJson;
    private String findingIds;
    private String title;
    private String description;
    private String error;
}
