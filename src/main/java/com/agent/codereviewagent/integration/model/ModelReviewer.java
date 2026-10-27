package com.agent.codereviewagent.integration.model;

import com.agent.codereviewagent.service.Reviewer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/** 将固定提交的 diff、当前正式依据及历史发现交给审查模型。 */
@Component
public class ModelReviewer implements Reviewer {

    private static final Set<String> CATEGORIES = Set.of("编码规范", "缺陷", "spec");
    private final ReviewModelClient model;

    public ModelReviewer(ReviewModelClient model) { this.model = model; }

    @Override
    public ReviewFindings review(ReviewRequest request) {
        ReviewFindings result = model.call("review", "review", request, ReviewFindings.class);
        if (result.drafts() == null || result.resolvedDiscussionIds() == null) {
            throw new IllegalStateException("审查模型结果缺少 drafts 或 resolvedDiscussionIds");
        }
        List<CommentDraft> accepted = new ArrayList<>();
        for (CommentDraft draft : result.drafts()) {
            if (draft != null && "spec".equals(draft.category()) && request.spec().skipped()) { continue; }
            if (draft == null || draft.category() == null || !CATEGORIES.contains(draft.category())
                    || draft.commentBody() == null || draft.commentBody().isBlank()
                    || (draft.newLine() != null && draft.newLine() <= 0)
                    || (draft.oldLine() != null && draft.oldLine() <= 0)
                    || ((draft.newLine() != null || draft.oldLine() != null)
                        && (draft.path() == null || draft.path().isBlank()))) {
                throw new IllegalStateException("审查模型返回的发现字段不符合约定");
            }
            accepted.add(draft);
        }
        for (Long discussionId : result.resolvedDiscussionIds()) {
            if (discussionId == null || discussionId <= 0
                    || request.priorFindings().stream().noneMatch(prior -> prior.discussionId() == discussionId)) {
                throw new IllegalStateException("审查模型返回了未知的已有讨论编号");
            }
        }
        return new ReviewFindings(List.copyOf(accepted), List.copyOf(result.resolvedDiscussionIds()));
    }
}
