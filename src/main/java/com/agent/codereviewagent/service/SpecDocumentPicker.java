package com.agent.codereviewagent.service;

import org.springframework.stereotype.Component;

import java.util.List;

/** 从文档目录清单里挑出这次审查要读的路径。 */
public interface SpecDocumentPicker {

    List<String> pick(List<String> candidates);

    @Component
    class EmptySpecDocumentPicker implements SpecDocumentPicker {

        @Override
        public List<String> pick(List<String> candidates) {
            return List.of();
        }
    }
}
