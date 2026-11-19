package com.agent.codereviewagent.models.dto;

import com.agent.codereviewagent.models.entity.WorkbenchJob;
import java.util.List;

/** 详情的公开投影；不得将持久化实体或内部错误字段加入返回值。 */
public record ReviewJobDetail(WorkbenchJob job, Repository repository, String changeUrl, Diff diff,
                              ReviewResult result, Failure failure, Retry retry) {
    public record Repository(long id, String platform, String fullName, String url) {}
    public record Diff(String headSha, String baseSha, String startSha) {}
    public record ReviewResult(String availability, Long reviewRecordId, String outcome, String occurredAt,
                               Integer publishedCount, Boolean specSkipped, List<String> specDocumentPaths,
                               List<String> standardDocumentPaths, List<Finding> findings) {}
    public record Finding(long id, String category, String path, Integer newLine, Integer oldLine,
                          String citation, String commentBody, String discussionUrl) {}
    public record Failure(String stage, String reason, boolean interrupted) {}
    public record Retry(boolean eligible, Long activeJobId) {}
}
