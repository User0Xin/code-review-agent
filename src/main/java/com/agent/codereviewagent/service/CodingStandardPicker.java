package com.agent.codereviewagent.service;

import java.util.List;

/** 从规范分支的文件清单里挑出编码规范。 */
public interface CodingStandardPicker {

    List<String> pick(List<String> candidates);

}
