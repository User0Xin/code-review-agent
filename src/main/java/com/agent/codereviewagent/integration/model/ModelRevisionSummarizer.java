package com.agent.codereviewagent.integration.model;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.RevisionSummarizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 按完整总结指令，把发现归类并产生已有规范的局部替换。 */
@Component
public class ModelRevisionSummarizer implements RevisionSummarizer {

    private final ReviewModelClient model;

    public ModelRevisionSummarizer(ReviewModelClient model) { this.model = model; }

    @Override
    public Summary summarize(List<FindingEntity> findings, List<Reviewer.StandardDocument> standards) {
        Summary result = model.call("summary", "summarize", Map.of("findings", findings, "standards", standards), Summary.class);
        if (result.replacements() == null) {
            throw new IllegalStateException("总结模型结果缺少 replacements");
        }
        for (Replacement replacement : result.replacements()) {
            if (replacement == null || blank(replacement.group()) || blank(replacement.path())
                    || blank(replacement.original()) || blank(replacement.replacement()) || blank(replacement.reason())
                    || replacement.findingIds() == null || replacement.findingIds().isEmpty()
                    || replacement.findingIds().stream().anyMatch(id -> id == null || id <= 0)) {
                throw new IllegalStateException("总结模型返回的替换字段不符合约定");
            }
        }
        return new Summary(List.copyOf(result.replacements()));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
