package com.agent.codereviewagent.integration.model;

import com.agent.codereviewagent.service.CodingStandardPicker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 每次从规范分支清单判定说明性编码规范，模型只返回候选路径。 */
@Component
public class ModelCodingStandardPicker implements CodingStandardPicker {

    private final ReviewModelClient model;

    public ModelCodingStandardPicker(ReviewModelClient model) { this.model = model; }

    @Override
    public List<String> pick(List<String> candidates) {
        if (candidates.isEmpty()) { return List.of(); }
        ModelSpecDocumentPicker.SelectedPaths selected = model.call("review", "select_coding_standards",
                Map.of("candidates", candidates), ModelSpecDocumentPicker.SelectedPaths.class);
        if (selected.paths() == null || selected.paths().stream().anyMatch(path -> path == null)) {
            throw new IllegalStateException("编码规范选择结果缺少有效 paths");
        }
        return List.copyOf(selected.paths());
    }
}
