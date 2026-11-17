package com.agent.codereviewagent.models.entity;

/** 工作台可展示的任务信息；不返回内部异常、凭据或模型输入。 */
public record WorkbenchJob(long id, long repositoryId, long number, String headSha,
                           String status, String source, String message, String createdAt, String finishedAt) { }
