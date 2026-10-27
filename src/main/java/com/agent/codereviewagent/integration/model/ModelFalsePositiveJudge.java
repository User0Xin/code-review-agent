package com.agent.codereviewagent.integration.model;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.FalsePositiveJudge;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 总结前独立读取整段外部回复的最终态度。 */
@Component
public class ModelFalsePositiveJudge implements FalsePositiveJudge {

    private final ReviewModelClient model;

    public ModelFalsePositiveJudge(ReviewModelClient model) { this.model = model; }

    @Override
    public Decision judge(FindingEntity finding, List<String> externalReplies) {
        Decision decision = model.call("false-positive", "judge_false_positive",
                Map.of("finding", finding, "replies", externalReplies), Decision.class);
        if (decision.falsePositive() == null || decision.reason() == null || decision.reason().isBlank()) {
            throw new IllegalStateException("误报模型返回的判定字段不符合约定");
        }
        if (externalReplies.isEmpty()) { return new Decision(false, "讨论没有外部回复，未被开发者判定为误报。"); }
        return decision;
    }
}
