package com.agent.codereviewagent.service;

import com.agent.codereviewagent.models.entity.FindingEntity;
import java.util.List;

/** 仅总结前逐条判断；已用于修订的发现不再重新判定。 */
public interface FalsePositiveJudge {
    Decision judge(FindingEntity finding, List<String> externalReplies);
    /** 判定针对开发者是否表示无需修改；讨论被标为已解决并不等于误报。 */
    record Decision(Boolean falsePositive, String reason) { }
}
