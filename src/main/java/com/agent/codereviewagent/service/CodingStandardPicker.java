package com.agent.codereviewagent.service;

import java.util.List;

/** 在规范分支的候选文档中挑选编码规范；没有合适文档时返回空列表。 */
public interface CodingStandardPicker {

    List<String> pick(List<String> candidates);

}
