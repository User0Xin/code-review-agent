package com.agent.codereviewagent.service;

import java.util.List;

/** 从本次提交的候选文档中选择 spec 路径；空列表表示跳过 spec 审查。 */
public interface SpecDocumentPicker {

    List<String> pick(List<String> candidates);

}
