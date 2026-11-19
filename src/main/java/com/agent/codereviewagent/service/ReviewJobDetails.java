package com.agent.codereviewagent.service;

import com.agent.codereviewagent.mapper.FindingMapper;
import com.agent.codereviewagent.mapper.ReviewRecordMapper;
import com.agent.codereviewagent.mapper.WorkbenchJobMapper;
import com.agent.codereviewagent.models.dto.ReviewJobDetail;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.models.entity.WorkbenchJob;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** 同一数据库读事务内按任务读取，不访问平台，不用版本或时间推测归属。 */
@Service
public class ReviewJobDetails {
    private final WorkbenchJobMapper jobs;
    private final RepositoryProfiles profiles;
    private final ReviewRecordMapper records;
    private final FindingMapper findings;

    public ReviewJobDetails(WorkbenchJobMapper jobs, RepositoryProfiles profiles,
                            ReviewRecordMapper records, FindingMapper findings) {
        this.jobs = jobs; this.profiles = profiles; this.records = records; this.findings = findings;
    }

    @Transactional(readOnly = true)
    public ReviewJobDetail get(long repositoryId, long jobId) {
        if (repositoryId <= 0 || jobId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库和任务编号必须为正整数");
        }
        var job = jobs.get(repositoryId, jobId);
        if (job == null) { throw missing(); }
        RepositoryProfile profile;
        try { profile = profiles.get(repositoryId); }
        catch (NoSuchElementException absent) { throw missing(); }
        var metadata = jobs.details(repositoryId, jobId);
        boolean legacy = metadata.detailsVersion() == null;
        ReviewRecordEntity record = legacy ? null : records.selectOne(Wrappers.<ReviewRecordEntity>lambdaQuery()
                .eq(ReviewRecordEntity::getWorkbenchJobId, jobId));
        List<FindingEntity> posted = legacy ? List.of() : findings.selectList(Wrappers.<FindingEntity>lambdaQuery()
                .eq(FindingEntity::getWorkbenchJobId, jobId).orderByAsc(FindingEntity::getId));
        if (record != null) { requireOwner(record.getProjectId(), record.getMergeRequestIid(), profile, job); }
        posted.forEach(f -> requireOwner(f.getProjectId(), f.getMergeRequestIid(), profile, job));
        String changeUrl = changeUrl(profile, job.number());
        String availability = legacy ? "LEGACY_UNLINKED" : record != null ? "RECORDED"
                : switch (job.status()) { case "QUEUED" -> "NOT_STARTED"; case "RUNNING" -> "IN_PROGRESS"; default -> "MISSING"; };
        var result = new ReviewJobDetail.ReviewResult(availability, record == null ? null : record.getId(),
                record == null ? null : record.getOutcome(), record == null ? null : record.getOccurredAt(),
                record == null ? null : record.getPublishedCount(), record == null ? null : record.getSpecSkipped(),
                record == null ? null : paths(record.getSpecDocumentPaths()),
                record == null ? null : paths(record.getStandardDocumentPaths()),
                posted.stream().map(f -> finding(f, profile, changeUrl)).toList());
        String stage = metadata.failureStage();
        String reason = job.message();
        if (stage == null && "INTERRUPTED".equals(ReviewFailureStage.fromReason(reason))) { stage = "INTERRUPTED"; }
        if (!"INTERRUPTED".equals(stage) && record != null && "FAILED".equals(record.getOutcome())) {
            stage = record.getFailureStage(); reason = record.getReason();
        }
        var failure = "FAILED".equals(job.status()) || (record != null && "FAILED".equals(record.getOutcome()))
                ? new ReviewJobDetail.Failure(ReviewFailureStage.safeStage(stage), ReviewFailureStage.safeReason(reason),
                    "INTERRUPTED".equals(stage)) : null;
        var active = jobs.active(repositoryId, job.number());
        var safeJob = new WorkbenchJob(job.id(), job.repositoryId(), job.number(), job.headSha(), job.status(),
                job.source(), ReviewFailureStage.safeMessage(job.message()), job.createdAt(), job.finishedAt());
        return new ReviewJobDetail(safeJob, new ReviewJobDetail.Repository(profile.id(), profile.platform(),
                profile.fullName(), profile.url()), changeUrl,
                new ReviewJobDetail.Diff(sha(job.headSha()), sha(metadata.baseSha()), sha(metadata.startSha())), result,
                failure, new ReviewJobDetail.Retry("FAILED".equals(job.status()), active == null ? null : active.id()));
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "评审任务不存在或不属于所选仓库");
    }

    private static void requireOwner(Long storageId, Long number, RepositoryProfile profile, WorkbenchJob job) {
        if (!Objects.equals(storageId, profile.storageId()) || !Objects.equals(number, job.number())) {
            throw new IllegalStateException("任务关联不一致");
        }
    }

    private static List<String> paths(String value) { return value == null ? null : value.isEmpty() ? List.of() : value.lines().toList(); }
    private static String sha(String value) { return value == null || value.isBlank() || "#awaiting-diff".equals(value) ? null : value; }

    private static String changeUrl(RepositoryProfile profile, long number) {
        var uri = URI.create(profile.url());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || number <= 0
                || !Set.of("github", "gitlab").contains(profile.platform())) {
            throw new IllegalStateException("仓库链接不可信");
        }
        return profile.url().replaceAll("/+$", "") + ("github".equals(profile.platform()) ? "/pull/" : "/-/merge_requests/") + number;
    }

    private static ReviewJobDetail.Finding finding(FindingEntity f, RepositoryProfile profile, String changeUrl) {
        if (f.getPublishedBody() == null) { throw new IllegalStateException("发布正文未记录"); }
        Long key = f.getDiscussionId();
        String url = null;
        if (key != null && key > 0) {
            if ("gitlab".equals(profile.platform())) { url = changeUrl + "#note_" + key; }
            else if (key / 2 > 0) { url = changeUrl + (key % 2 == 1 ? "#issuecomment-" : "#discussion_r") + key / 2; }
        }
        return new ReviewJobDetail.Finding(f.getId(), Set.of("编码规范", "缺陷", "spec").contains(Objects.toString(f.getCategory(), ""))
                ? f.getCategory() : null, f.getPath(), positive(f.getNewLine()), positive(f.getOldLine()),
                f.getCitation(), f.getPublishedBody(), url);
    }

    private static Integer positive(Integer value) { return value != null && value > 0 ? value : null; }
}
