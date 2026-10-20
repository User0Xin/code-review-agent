package com.agent.codereviewagent.service;

import org.springframework.stereotype.Component;

import java.util.List;

/** 从规范分支的文件清单里挑出编码规范。 */
public interface CodingStandardPicker {

    List<String> pick(List<String> candidates);

    @Component
    class EmptyCodingStandardPicker implements CodingStandardPicker {

        @Override
        public List<String> pick(List<String> candidates) {
            return List.of();
        }
    }
}
