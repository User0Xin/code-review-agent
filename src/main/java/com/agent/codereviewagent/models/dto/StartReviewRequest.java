package com.agent.codereviewagent.models.dto;

/** 编号始终相对于路径中的被审查仓库。 */
public record StartReviewRequest(Long number) { }
