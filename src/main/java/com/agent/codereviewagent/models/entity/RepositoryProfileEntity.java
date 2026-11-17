package com.agent.codereviewagent.models.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 管理员配置的评审仓库；路径逐行保存，输入校验禁止换行符。 */
@Data
@TableName("repository_profile")
public class RepositoryProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private Long remoteId;
    private String fullName;
    private String url;
    private String standardPaths;
    private String specPaths;
}
