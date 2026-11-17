package com.agent.codereviewagent.models.dto;

/** 传入 UTF-8 SKILL.md 正文；只有 enabled 时保留原有导入内容。 */
public record SkillImportRequest(String content, Boolean enabled) {}
