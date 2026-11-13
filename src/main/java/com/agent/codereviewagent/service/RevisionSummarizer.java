package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.FindingEntity;
import java.util.List;

/** 总结时才归类，并给出对现有规范的精确替换。 */
public interface RevisionSummarizer {
    Summary summarize(List<FindingEntity> findings, List<Reviewer.StandardDocument> standards);

    record Summary(List<Replacement> replacements) { }
    /** 一项替换必须指向已有规范中的具体原文，并给出支持这项改动的发现 ID 和理由。 */
    record Replacement(String group, String path, String original, String replacement,
                       List<Long> findingIds, String reason) { }
}
