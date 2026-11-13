package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.config.GithubProperties;
import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.CodingStandardSource;
import com.agent.codereviewagent.service.CodingStandardRevisionService;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.agent.codereviewagent.service.RepositoryClient;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.SpecDocumentPicker;
import com.agent.codereviewagent.utils.ReviewClock;
import com.agent.codereviewagent.utils.Sleeper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 编排一次合并请求审查：按 webhook 指定的提交等候 diff，结合历史讨论和仓库文档审查，再写回评论并记录结果。
 * 同一服务实例串行处理事件，后一轮读取历史发现时，前一轮的写回已经结束。
 */
@Service
public class MergeRequestReviewServiceImpl implements MergeRequestReviewService {

    static final String DIFF_NOT_READY = "合并请求的 diff 尚未就绪";

    private final GitlabClient gitlabClient;
    private final RepositoryClient githubClient;
    private final GithubProperties githubProperties;
    private final Reviewer reviewer;
    private final ReviewRecordService reviewRecordService;
    private final FindingService findingService;
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
                                         SpecDocumentPicker specDocumentPicker,
                                         CodingStandardSource standardSource,
                                         CodingStandardRevisionService revisions,
                                         ReviewClock clock,
                                         Sleeper sleeper,
                                         DiffWaitProperties diffWait,
                                         GitlabProperties gitlabProperties,
                                         @Qualifier("githubClient") RepositoryClient githubClient,
                                         GithubProperties githubProperties) {
        this.gitlabClient = gitlabClient;
        this.githubClient = githubClient;
        this.githubProperties = githubProperties;
        this.reviewer = reviewer;
        this.reviewRecordService = reviewRecordService;
        this.findingService = findingService;
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
        ReviewContext context = new ReviewContext(gitlabClient, projectId, projectId, mergeRequestIid, attributes.action());
        String expected = attributes.lastCommit() == null ? null : attributes.lastCommit().id();
        try {
            runReview(context, attributes.description(), expected);
        }
        finally {
            // 修订检查面向整个项目，即使本轮审查失败，也可能有前几轮积累的发现待处理。
            revisions.checkAfterReview(projectId, mergeRequestIid);
        }
    }

    @Override
    public synchronized void acceptGithub(long repositoryId, long number, String action, String description, String headSha) {
        if (repositoryId <= 0 || repositoryId != githubProperties.getRepositoryId() || number <= 0
                || headSha == null || headSha.isBlank() || action == null
                || !List.of("opened", "reopened", "synchronize").contains(action)) { return; }
        // SQLite 历史记录以正数 GitLab 项目编号为键。负键给 GitHub 独立的命名空间；
        // 发给平台和模型的 repositoryId 始终是平台原生的正数。
        runReview(new ReviewContext(githubClient, repositoryId, -repositoryId, number, action), description, headSha);
    }

    private void runReview(ReviewContext context, String description, String expected) {
        context.headSha = expected;
        try {
            review(context, description, expected);
        }
        catch (RuntimeException exception) {
            fail(context, context.phase, exception);
        }
    }

    private void review(ReviewContext context, String mergeRequestDescription, String expected) {
        long projectId = context.projectId;
        long mergeRequestIid = context.mergeRequestIid;
        context.phase = "diff 读取失败";
        RepositoryClient.DiffRefs diffRefs = waitForDiff(context, expected);
        if (diffRefs == null) {
            fail(context, DIFF_NOT_READY, null);
            return;
        }
        context.headSha = diffRefs.headSha();
        context.phase = "已有讨论读取失败";
        List<FindingEntity> existing = findingService.findByMergeRequest(context.storageId, mergeRequestIid);
        List<Reviewer.PriorFinding> priorFindings = priorFindings(context, existing);
        context.phase = "编码规范读取失败";
        context.standards = standardSource.load(context.client, projectId).standards();
        context.phase = "spec 读取失败";
        context.spec = loadSpec(context, mergeRequestDescription, diffRefs.headSha(), context.standards);
        context.phase = "diff 正文读取失败";
        String diff = context.client.readDiff(projectId, mergeRequestIid, diffRefs);
        context.phase = "模型审查失败";
        Reviewer.ReviewFindings findings = reviewer.review(new Reviewer.ReviewRequest(
                projectId, mergeRequestIid, diffRefs.headSha(), priorFindings, context.spec, context.standards, diff));
        context.phase = "提交状态核对失败";
        // 模型返回时合并请求可能已更新；行评论的定位依赖整组 diff 引用，仅核对 head 不够。
        var current = context.client.diffRefs(projectId, mergeRequestIid);
        if (current.isEmpty() || !diffRefs.equals(current.get())) {
            fail(context, "审查期间提交已变化", null);
            return;
        }
        List<Long> resolved = findings.resolvedDiscussionIds() == null ? List.of() : findings.resolvedDiscussionIds();
        List<Long> declined = findings.declinedDiscussionIds() == null ? List.of() : findings.declinedDiscussionIds();
        context.phase = "评论写回失败";
        int failed = postDrafts(context, diffRefs,
                stillOpen(findings.drafts(), priorFindings, resolved, declined));
        context.phase = "讨论状态更新失败";
        resolveKnownDiscussions(context, existing, resolved, declined);
        if (failed > 0) {
            fail(context, "有 " + failed + " 条没能写回", null);
            return;
        }
        ReviewRecordEntity record = reviewRecord(context, null, clock.now());
        // 清理通知失败不抹掉本次成功审查，旧索引保留到下一次重试。
        var old = reviewRecordService.findFailureNoteId(context.storageId, mergeRequestIid);
        if (old.isPresent()) {
            try {
                context.client.deleteNote(projectId, mergeRequestIid, old.get());
                reviewRecordService.forgetFailureNote(context.storageId, mergeRequestIid);
            }
            catch (RuntimeException exception) {
                record.setNoteError("失败说明清理失败：" + exception.getClass().getSimpleName());
            }
        }
        reviewRecordService.recordSuccess(record);
    }

    /** 本轮完全相同的草稿只发布一次；单条发布失败计数后，继续处理其余草稿。 */
    private int postDrafts(ReviewContext context, RepositoryClient.DiffRefs refs, List<Reviewer.CommentDraft> drafts) {
        int failed = 0;
        for (Reviewer.CommentDraft draft : drafts.stream().distinct().toList()) {
            long discussionId;
            try {
                discussionId = postOne(context, refs, draft);
            }
            catch (RuntimeException exception) {
                failed++;
                continue;
            }
            try {
                findingService.recordPostedFinding(finding(
                        context.storageId,
                        context.mergeRequestIid,
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
                // 平台写回与本地入库不能共用事务；入库失败时尝试删除刚发布的评论。
                context.client.deleteNote(context.projectId, context.mergeRequestIid, discussionId);
                failed++;
            }
        }
        return failed;
    }

    private long postOne(ReviewContext context, RepositoryClient.DiffRefs refs, Reviewer.CommentDraft draft) {
        if (draft.newLine() != null || draft.oldLine() != null) {
            return context.client.createLineDiscussion(
                    context.projectId,
                    context.mergeRequestIid,
                    draft.path(),
                    draft.newLine(),
                    draft.oldLine(),
                    refs,
                    draft.commentBody());
        }
        return context.client.createOverviewDiscussion(context.projectId, context.mergeRequestIid, draft.commentBody());
    }

    /** 本地记录标识本服务发过的评论；向对应平台读取讨论的最新回复和解决状态。 */
    private List<Reviewer.PriorFinding> priorFindings(ReviewContext context,
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
                    context.client.listReplies(context.projectId, context.mergeRequestIid, discussionId),
                    context.client.isDiscussionResolved(context.projectId, context.mergeRequestIid, discussionId)));
        }
        return prior;
    }

    /**
     * 原讨论仍未解决，或作者已表明无需修改时，不重复发评论。
     * 匹配依据是路径、类别及相同的正文或讨论编号；行号不参与，因此不同位置的相同评论也可能被过滤。
     */
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
    private void resolveKnownDiscussions(ReviewContext context,
                                         List<FindingEntity> existing,
                                         List<Long> resolved,
                                         List<Long> declined) {
        for (Long discussionId : resolved) {
            if (discussionId == null || declined.contains(discussionId)) {
                continue;
            }
            boolean known = existing.stream().anyMatch(finding -> discussionId.equals(finding.getDiscussionId()));
            if (known) {
                context.client.resolveDiscussion(context.projectId, context.mergeRequestIid, discussionId);
            }
        }
    }

    private Reviewer.Spec loadSpec(ReviewContext context,
                                   String mergeRequestDescription,
                                   String headSha,
                                   Reviewer.CodingStandards standards) {
        List<Reviewer.SpecDocument> documents = new ArrayList<>();
        // spec 使用被审查提交中的文档；已作为编码规范的路径不再作为需求依据。
        List<String> candidates = context.client.listFiles(context.projectId, headSha).stream()
                .filter(MergeRequestReviewServiceImpl::specDocument)
                .filter(path -> standards.documents().stream().noneMatch(document -> document.path().equals(path)))
                .toList();
        for (String path : specDocumentPicker.pick(candidates)) {
            if (!candidates.contains(path) || documents.stream().anyMatch(document -> document.path().equals(path))) {
                continue;
            }
            documents.add(new Reviewer.SpecDocument(path, context.client.readFile(context.projectId, path, headSha)));
        }
        return new Reviewer.Spec(List.copyOf(documents), mergeRequestDescription, documents.isEmpty());
    }

    /** 若 webhook 指定了提交，要等 diff 的 head 与它匹配；耗尽尝试次数后返回 null。 */
    private RepositoryClient.DiffRefs waitForDiff(ReviewContext context, String expectedSha) {
        int attempts = Math.max(diffWait.getMaxAttempts(), 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleeper.sleep(diffWait.getInterval());
            }
            var ready = context.client.diffRefs(context.projectId, context.mergeRequestIid);
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
        Long noteId = reviewRecordService.findFailureNoteId(context.storageId, context.mergeRequestIid).orElse(null);
        boolean canCreate = true;
        if (noteId != null) {
            try {
                context.client.deleteNote(context.projectId, context.mergeRequestIid, noteId);
                reviewRecordService.forgetFailureNote(context.storageId, context.mergeRequestIid);
                noteId = null;
            }
            catch (RuntimeException exception) {
                // 旧通知可能仍在，保留其编号，避免重复发布失败说明。
                canCreate = false;
                record.setNoteError("旧失败说明删除失败：" + exception.getClass().getSimpleName());
            }
        }
        if (canCreate) {
            try {
                noteId = context.client.createOverviewDiscussion(context.projectId, context.mergeRequestIid,
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
        record.setProjectId(context.storageId);
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
        private final RepositoryClient client;
        private final long storageId;
        private final long projectId;
        private final long mergeRequestIid;
        private final String trigger;
        private String headSha;
        private String phase = "审查执行失败";
        private Reviewer.Spec spec;
        private Reviewer.CodingStandards standards;
        private ReviewContext(RepositoryClient client, long projectId, long storageId, long mergeRequestIid, String trigger) {
            this.client = client;
            this.projectId = projectId;
            this.storageId = storageId;
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
