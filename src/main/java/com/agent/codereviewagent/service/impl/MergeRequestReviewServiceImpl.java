package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.CodingStandardSource;
import com.agent.codereviewagent.service.CodingStandardRevisionService;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.SpecDocumentPicker;
import com.agent.codereviewagent.service.WorkItemClient;
import com.agent.codereviewagent.utils.ReviewClock;
import com.agent.codereviewagent.utils.Sleeper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 决定一条合并请求 webhook 要不要审查。
 * diff 就绪且没有发现时记成功，并删掉之前的失败概览讨论；超过等待次数则只留一条失败讨论。
 */
@Service
public class MergeRequestReviewServiceImpl implements MergeRequestReviewService {

    static final String DIFF_NOT_READY = "合并请求的 diff 尚未就绪";

    private final GitlabClient gitlabClient;
    private final Reviewer reviewer;
    private final ReviewRecordService reviewRecordService;
    private final FindingService findingService;
    private final WorkItemClient workItemClient;
    private final SpecDocumentPicker specDocumentPicker;
    private final CodingStandardSource standardSource;
    private final CodingStandardRevisionService revisions;
    private final ReviewClock clock;
    private final Sleeper sleeper;
    private final DiffWaitProperties diffWait;
    private final GitlabProperties gitlabProperties;

    public MergeRequestReviewServiceImpl(GitlabClient gitlabClient,
                                         Reviewer reviewer,
                                         ReviewRecordService reviewRecordService,
                                         FindingService findingService,
                                         WorkItemClient workItemClient,
                                         SpecDocumentPicker specDocumentPicker,
                                         CodingStandardSource standardSource,
                                         CodingStandardRevisionService revisions,
                                         ReviewClock clock,
                                         Sleeper sleeper,
                                         DiffWaitProperties diffWait,
                                         GitlabProperties gitlabProperties) {
        this.gitlabClient = gitlabClient;
        this.reviewer = reviewer;
        this.reviewRecordService = reviewRecordService;
        this.findingService = findingService;
        this.workItemClient = workItemClient;
        this.specDocumentPicker = specDocumentPicker;
        this.standardSource = standardSource;
        this.revisions = revisions;
        this.clock = clock;
        this.sleeper = sleeper;
        this.diffWait = diffWait;
        this.gitlabProperties = gitlabProperties;
    }

    @Override
    public synchronized void accept(String gitlabEvent, MergeRequestWebhookRequest request) {
        // 只处理 GitLab 的合并请求事件。别的 hook、空 body 直接忽略，也不留审查记录。
        if (!"Merge Request Hook".equals(gitlabEvent)
                || request == null
                || !"merge_request".equals(request.objectKind())) {
            return;
        }
        MergeRequestWebhookRequest.ObjectAttributes attributes = request.objectAttributes();
        if (attributes == null || !startsReview(attributes)) {
            return;
        }
        MergeRequestWebhookRequest.Project project = request.project();
        long projectId = project == null || project.id() == null ? 0 : project.id();
        long mergeRequestIid = attributes.iid() == null ? 0 : attributes.iid();
        // 缺少项目或合并请求编号时无法写回，也不记审查记录。
        if (projectId <= 0 || mergeRequestIid <= 0
                || (gitlabProperties.getProjectId() > 0 && gitlabProperties.getProjectId() != projectId)) {
            return;
        }
        ReviewContext context = new ReviewContext(projectId, mergeRequestIid, attributes.action());
        String expected = attributes.lastCommit() == null ? null : attributes.lastCommit().id();
        context.headSha = expected;
        try {
            review(context, attributes, expected);
        }
        catch (RuntimeException exception) {
            fail(context, context.phase, exception);
        }
        finally {
            revisions.checkAfterReview(projectId, mergeRequestIid);
        }
    }

    private void review(ReviewContext context, MergeRequestWebhookRequest.ObjectAttributes attributes, String expected) {
        long projectId = context.projectId;
        long mergeRequestIid = context.mergeRequestIid;
        context.phase = "diff 读取失败";
        GitlabClient.DiffRefs diffRefs = waitForDiff(projectId, mergeRequestIid, expected);
        if (diffRefs == null) {
            fail(context, DIFF_NOT_READY, null);
            return;
        }
        context.headSha = diffRefs.headSha();
        context.phase = "已有讨论读取失败";
        List<FindingEntity> existing = findingService.findByMergeRequest(projectId, mergeRequestIid);
        List<Reviewer.PriorFinding> priorFindings = priorFindings(projectId, mergeRequestIid, existing);
        context.phase = "编码规范读取失败";
        context.standards = loadStandards(projectId);
        context.phase = "spec 读取失败";
        context.spec = loadSpec(projectId, attributes, diffRefs.headSha(), context.standards);
        context.phase = "diff 正文读取失败";
        String diff = gitlabClient.readDiff(projectId, mergeRequestIid, diffRefs);
        context.phase = "模型审查失败";
        Reviewer.ReviewFindings findings = reviewer.review(new Reviewer.ReviewRequest(
                projectId, mergeRequestIid, diffRefs.headSha(), priorFindings, context.spec, context.standards, diff));
        context.phase = "提交状态核对失败";
        var current = gitlabClient.diffRefs(projectId, mergeRequestIid);
        if (current.isEmpty() || !diffRefs.equals(current.get())) {
            fail(context, "审查期间提交已变化", null);
            return;
        }
        List<Long> resolved = findings.resolvedDiscussionIds() == null ? List.of() : findings.resolvedDiscussionIds();
        List<Long> declined = findings.declinedDiscussionIds() == null ? List.of() : findings.declinedDiscussionIds();
        context.phase = "评论写回失败";
        int failed = postDrafts(projectId, mergeRequestIid, diffRefs,
                stillOpen(findings.drafts(), priorFindings, resolved, declined));
        context.phase = "讨论状态更新失败";
        resolveKnownDiscussions(projectId, mergeRequestIid, existing, resolved, declined);
        if (failed > 0) {
            fail(context, "有 " + failed + " 条没能写回", null);
            return;
        }
        ReviewRecordEntity record = reviewRecord(context, null, clock.now());
        // 清理通知失败不抹掉本次成功审查，旧索引保留到下一次重试。
        var old = reviewRecordService.findFailureNoteId(projectId, mergeRequestIid);
        if (old.isPresent()) {
            try {
                gitlabClient.deleteNote(projectId, mergeRequestIid, old.get());
                reviewRecordService.forgetFailureNote(projectId, mergeRequestIid);
            }
            catch (RuntimeException exception) {
                record.setNoteError("失败说明清理失败：" + exception.getClass().getSimpleName());
            }
        }
        reviewRecordService.recordSuccess(record);
    }

    /** 每条评论只写回一次。没写上的不记为发现，已经写上并保存的不撤回。 */
    private int postDrafts(long projectId, long mergeRequestIid, GitlabClient.DiffRefs refs, java.util.List<Reviewer.CommentDraft> drafts) {
        int failed = 0;
        for (Reviewer.CommentDraft draft : drafts.stream().distinct().toList()) {
            long discussionId;
            try {
                discussionId = postOne(projectId, mergeRequestIid, refs, draft);
            }
            catch (RuntimeException exception) {
                failed++;
                continue;
            }
            try {
                findingService.recordPostedFinding(finding(
                        projectId,
                        mergeRequestIid,
                        discussionId,
                        draft.path(),
                        draft.newLine(),
                        draft.oldLine(),
                        draft.category(),
                        draft.citation(),
                        draft.commentBody(),
                        refs.headSha()));
            }
            catch (RuntimeException exception) {
                gitlabClient.deleteNote(projectId, mergeRequestIid, discussionId);
                failed++;
            }
        }
        return failed;
    }

    private long postOne(long projectId, long mergeRequestIid, GitlabClient.DiffRefs refs, Reviewer.CommentDraft draft) {
        if (draft.newLine() != null || draft.oldLine() != null) {
            return gitlabClient.createLineDiscussion(
                    projectId,
                    mergeRequestIid,
                    draft.path(),
                    draft.newLine(),
                    draft.oldLine(),
                    refs,
                    draft.commentBody());
        }
        return gitlabClient.createOverviewDiscussion(projectId, mergeRequestIid, draft.commentBody());
    }

    private List<Reviewer.PriorFinding> priorFindings(long projectId,
                                                      long mergeRequestIid,
                                                      List<FindingEntity> existing) {
        List<Reviewer.PriorFinding> prior = new ArrayList<>();
        for (FindingEntity finding : existing) {
            long discussionId = finding.getDiscussionId();
            prior.add(new Reviewer.PriorFinding(
                    discussionId,
                    finding.getPath(),
                    finding.getNewLine(),
                    finding.getOldLine(),
                    finding.getCategory(),
                    finding.getCommentBody(),
                    gitlabClient.listReplies(projectId, mergeRequestIid, discussionId),
                    gitlabClient.isDiscussionResolved(projectId, mergeRequestIid, discussionId)));
        }
        return prior;
    }

    /** 仍开着的同一问题，以及作者已表明无需修改的问题，不再另开讨论。行号变化不算新问题。 */
    private static List<Reviewer.CommentDraft> stillOpen(List<Reviewer.CommentDraft> drafts,
                                                         List<Reviewer.PriorFinding> priorFindings,
                                                         List<Long> resolved,
                                                         List<Long> declined) {
        List<Reviewer.CommentDraft> kept = new ArrayList<>();
        for (Reviewer.CommentDraft draft : drafts) {
            if (!repeatsOpenOrDeclined(draft, priorFindings, resolved, declined)) {
                kept.add(draft);
            }
        }
        return kept;
    }

    private static boolean repeatsOpenOrDeclined(Reviewer.CommentDraft draft,
                                                 List<Reviewer.PriorFinding> priorFindings,
                                                 List<Long> resolved,
                                                 List<Long> declined) {
        for (Reviewer.PriorFinding prior : priorFindings) {
            if (!java.util.Objects.equals(draft.path(), prior.path())
                    || !java.util.Objects.equals(draft.category(), prior.category())) {
                continue;
            }
            boolean sameProblem = java.util.Objects.equals(draft.commentBody(), prior.commentBody())
                    || java.util.Objects.equals(draft.priorDiscussionId(), prior.discussionId());
            if (sameProblem && (declined.contains(prior.discussionId())
                    || (!prior.resolved() && !resolved.contains(prior.discussionId())))) {
                return true;
            }
        }
        return false;
    }

    /** 只把这次 diff 消除的、且本来就存在的讨论标成已解决。不删除发现，也不删讨论。 */
    private void resolveKnownDiscussions(long projectId,
                                         long mergeRequestIid,
                                         List<FindingEntity> existing,
                                         List<Long> resolved,
                                         List<Long> declined) {
        for (Long discussionId : resolved) {
            if (discussionId == null || declined.contains(discussionId)) {
                continue;
            }
            boolean known = existing.stream().anyMatch(finding -> discussionId.equals(finding.getDiscussionId()));
            if (known) {
                gitlabClient.resolveDiscussion(projectId, mergeRequestIid, discussionId);
            }
        }
    }

    private Reviewer.CodingStandards loadStandards(long projectId) {
        return standardSource.load(projectId).standards();
    }

    private Reviewer.Spec loadSpec(long projectId,
                                   MergeRequestWebhookRequest.ObjectAttributes attributes,
                                   String headSha,
                                   Reviewer.CodingStandards standards) {
        String workItemText = workItemId(attributes.sourceBranch())
                .flatMap(workItemClient::readPlainText)
                .orElse(null);
        java.util.ArrayList<Reviewer.SpecDocument> documents = new java.util.ArrayList<>();
        java.util.List<String> candidates = gitlabClient.listFiles(projectId, headSha).stream()
                .filter(MergeRequestReviewServiceImpl::specDocument)
                .filter(path -> standards.documents().stream().noneMatch(document -> document.path().equals(path)))
                .toList();
        for (String path : specDocumentPicker.pick(candidates)) {
            if (!candidates.contains(path) || documents.stream().anyMatch(document -> document.path().equals(path))) {
                continue;
            }
            documents.add(new Reviewer.SpecDocument(path, gitlabClient.readFile(projectId, path, headSha)));
        }
        boolean skipped = workItemText == null && documents.isEmpty();
        return new Reviewer.Spec(workItemText, java.util.List.copyOf(documents), attributes.description(), skipped);
    }

    /** 立刻读一次 diff。没就绪再按间隔重试，总次数至少为 1。始终没有则返回 null。 */
    private GitlabClient.DiffRefs waitForDiff(long projectId, long mergeRequestIid, String expectedSha) {
        int attempts = Math.max(diffWait.getMaxAttempts(), 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleeper.sleep(diffWait.getInterval());
            }
            var ready = gitlabClient.diffRefs(projectId, mergeRequestIid);
            if (ready.isPresent() && (expectedSha == null || expectedSha.isBlank()
                    || expectedSha.equals(ready.get().headSha()))) {
                return ready.get();
            }
        }
        return null;
    }

    /** 通知维护失败仍保存主审查结果，并保留可供下次清理的索引。 */
    private void fail(ReviewContext context, String reason, RuntimeException failure) {
        ReviewRecordEntity record = reviewRecord(context, reason, clock.now());
        if (failure != null) {
            // 保存错误类型与代码位置，不保存可能携带提示词、模型原文或凭据的异常消息。
            record.setFailureDetail(failure.getClass().getName() + "\n"
                    + java.util.Arrays.stream(failure.getStackTrace()).map(Object::toString)
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        Long noteId = reviewRecordService.findFailureNoteId(context.projectId, context.mergeRequestIid).orElse(null);
        boolean canCreate = true;
        if (noteId != null) {
            try {
                gitlabClient.deleteNote(context.projectId, context.mergeRequestIid, noteId);
                reviewRecordService.forgetFailureNote(context.projectId, context.mergeRequestIid);
                noteId = null;
            }
            catch (RuntimeException exception) {
                canCreate = false;
                record.setNoteError("旧失败说明删除失败：" + exception.getClass().getSimpleName());
            }
        }
        if (canCreate) {
            try {
                noteId = gitlabClient.createOverviewDiscussion(context.projectId, context.mergeRequestIid,
                        failureBody(reason, Instant.parse(record.getOccurredAt())));
            }
            catch (RuntimeException exception) {
                record.setNoteError("失败说明写回失败：" + exception.getClass().getSimpleName());
            }
        }
        reviewRecordService.recordFailure(record, noteId);
    }

    private static ReviewRecordEntity reviewRecord(ReviewContext context, String reason, Instant occurredAt) {
        ReviewRecordEntity record = new ReviewRecordEntity();
        record.setProjectId(context.projectId);
        record.setMergeRequestIid(context.mergeRequestIid);
        record.setTrigger(context.trigger);
        record.setHeadSha(context.headSha);
        record.setReason(reason);
        record.setOccurredAt(occurredAt.toString());
        if (context.spec != null) {
            record.setSpecSkipped(context.spec.skipped());
            record.setSpecDocumentPaths(context.spec.documents().stream().map(Reviewer.SpecDocument::path)
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        if (context.standards != null) {
            record.setStandardDocumentPaths(context.standards.documents().stream().map(Reviewer.StandardDocument::path)
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        return record;
    }

    private static final class ReviewContext {
        private final long projectId;
        private final long mergeRequestIid;
        private final String trigger;
        private String headSha;
        private String phase = "审查执行失败";
        private Reviewer.Spec spec;
        private Reviewer.CodingStandards standards;
        private ReviewContext(long projectId, long mergeRequestIid, String trigger) {
            this.projectId = projectId;
            this.mergeRequestIid = mergeRequestIid;
            this.trigger = trigger;
        }
    }

    private static FindingEntity finding(long projectId,
                                         long mergeRequestIid,
                                         long discussionId,
                                         String path,
                                         Integer newLine,
                                         Integer oldLine,
                                         String category,
                                         String citation,
                                         String commentBody,
                                         String headSha) {
        FindingEntity finding = new FindingEntity();
        finding.setProjectId(projectId);
        finding.setMergeRequestIid(mergeRequestIid);
        finding.setDiscussionId(discussionId);
        finding.setPath(path);
        finding.setNewLine(newLine);
        finding.setOldLine(oldLine);
        finding.setCategory(category);
        finding.setCitation(citation);
        finding.setCommentBody(commentBody);
        finding.setHeadSha(headSha);
        return finding;
    }

    /** 失败讨论正文只有原因和时间。 */
    static String failureBody(String reason, Instant occurredAt) {
        return "审查失败：" + reason + "。" + occurredAt;
    }

    /** 从源分支名取出工作项 ID，例如 feature/7058321625-openapi。 */
    private static Optional<String> workItemId(String sourceBranch) {
        if (sourceBranch == null || sourceBranch.isBlank()) {
            return Optional.empty();
        }
        String name = sourceBranch;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dash = name.indexOf('-');
        if (dash <= 0) {
            return Optional.empty();
        }
        String id = name.substring(0, dash);
        return id.chars().allMatch(Character::isDigit) ? Optional.of(id) : Optional.empty();
    }

    /** 仓库文档里可以进入 spec 的路径。 */
    private static boolean specDocument(String path) {
        if (path == null || path.isBlank() || path.contains("..")) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("docs/")) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("vendor")
                    || segment.equals("node_modules")
                    || segment.equals(".git")
                    || segment.equals("dist")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 打开合并请求，或更新里带了 oldrev（源分支有新提交）时才审查。
     * 改标题、指派人这类不带新代码的更新不审查。
     */
    private static boolean startsReview(MergeRequestWebhookRequest.ObjectAttributes attributes) {
        String action = attributes.action();
        if ("open".equals(action)) {
            return true;
        }
        if (!"update".equals(action)) {
            return false;
        }
        String oldrev = attributes.oldrev();
        return oldrev != null && !oldrev.isBlank();
    }
}
