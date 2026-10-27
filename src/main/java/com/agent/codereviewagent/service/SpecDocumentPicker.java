package com.agent.codereviewagent.service;

import java.util.List;

/** 从文档目录清单里挑出这次审查要读的路径。 */
public interface SpecDocumentPicker {

    List<String> pick(List<String> candidates);

}
