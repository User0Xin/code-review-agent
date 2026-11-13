package com.agent.codereviewagent.integration.model;

import com.agent.codereviewagent.service.SpecDocumentPicker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 只把候选路径发给模型；审查服务核对返回的路径后，按本次审查的提交读取 spec 正文。 */
@Component
public class ModelSpecDocumentPicker implements SpecDocumentPicker {

    private final ReviewModelClient model;

    public ModelSpecDocumentPicker(ReviewModelClient model) { this.model = model; }

    @Override
    public List<String> pick(List<String> candidates) {
        if (candidates.isEmpty()) { return List.of(); }
        SelectedPaths selected = model.call("review", "select_spec_documents",
                Map.of("candidates", candidates), SelectedPaths.class);
        if (selected.paths() == null || selected.paths().stream().anyMatch(path -> path == null)) {
            throw new IllegalStateException("spec 文档选择结果缺少有效 paths");
        }
        return List.copyOf(selected.paths());
    }

    public record SelectedPaths(List<String> paths) { }
}
