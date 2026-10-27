package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.config.StandardsBranchProperties;
import com.agent.codereviewagent.models.dto.MergeRequestWebhookRequest;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.CodingStandardPicker;
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
    private final CodingStandardPicker codingStandardPicker;
    private final StandardsBranchProperties standardsBranch;
    private final ReviewClock clock;
    private final Sleeper sleeper;
    private final DiffWaitProperties diffWait;

    public MergeRequestReviewServiceImpl(GitlabClient gitlabClient,
                                         Reviewer reviewer,
                                         ReviewRecordService reviewRecordService,
                                         FindingService findingService,
                                         WorkItemClient workItemClient,
                                         SpecDocumentPicker specDocumentPicker,
                                         CodingStandardPicker codingStandardPicker,
                                         StandardsBranchProperties standardsBranch,
                                         ReviewClock clock,
                                         Sleeper sleeper,
                                         DiffWaitProperties diffWait) {
        this.gitlabClient = gitlabClient;
        this.reviewer = reviewer;
        this.reviewRecordService = reviewRecordService;
        this.findingService = findingService;
        this.workItemClient = workItemClient;
        this.specDocumentPicker = specDocumentPicker;
        this.codingStandardPicker = codingStandardPicker;
        this.standardsBranch = standardsBranch;
        this.clock = clock;
        this.sleeper = sleeper;
        this.diffWait = diffWait;
    }

    @Override
    public void accept(String gitlabEvent, MergeRequestWebhookRequest request) {
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
        if (projectId == 0 || mergeRequestIid == 0) {
            return;
        }
        GitlabClient.DiffRefs diffRefs = waitForDiff(projectId, mergeRequestIid);
        if (diffRefs == null) {
            fail(projectId, mergeRequestIid, DIFF_NOT_READY, null, null);
            return;
        }
        List<FindingEntity> existing = findingService.findByMergeRequest(projectId, mergeRequestIid);
        List<Reviewer.PriorFinding> priorFindings = priorFindings(projectId, mergeRequestIid, existing);
        Reviewer.CodingStandards standards = loadStandards(projectId);
        Reviewer.Spec spec = loadSpec(projectId, attributes, diffRefs.headSha(), standards);
        Reviewer.ReviewFindings findings = reviewer.review(new Reviewer.ReviewRequest(
                projectId, mergeRequestIid, diffRefs.headSha(), priorFindings, spec, standards));
        List<Long> resolved = findings.resolvedDiscussionIds() == null
                ? List.of()
                : findings.resolvedDiscussionIds();
        int failed = postDrafts(
                projectId,
                mergeRequestIid,
                diffRefs.headSha(),
                stillOpen(findings.drafts(), existing, priorFindings, resolved));
        resolveKnownDiscussions(projectId, mergeRequestIid, existing, priorFindings, resolved);
        if (failed > 0) {
            fail(projectId, mergeRequestIid, "有 " + failed + " 条没能写回", spec, standards);
            return;
        }
        reviewRecordService.recordSuccess(reviewRecord(projectId, mergeRequestIid, null, clock.now(), spec, standards));
        // 已写回的发现都留下。若之前留过失败讨论，删掉它。
        reviewRecordService.findFailureNoteId(projectId, mergeRequestIid).ifPresent(noteId -> {
            gitlabClient.deleteNote(projectId, mergeRequestIid, noteId);
            reviewRecordService.forgetFailureNote(projectId, mergeRequestIid);
        });
    }

    /** 每条评论只写回一次。没写上的不记为发现，已经写上并保存的不撤回。 */
    private int postDrafts(long projectId, long mergeRequestIid, String headSha, java.util.List<Reviewer.CommentDraft> drafts) {
        int failed = 0;
        for (Reviewer.CommentDraft draft : drafts.stream().distinct().toList()) {
            long discussionId;
            try {
                discussionId = postOne(projectId, mergeRequestIid, headSha, draft);
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
                        headSha));
            }
            catch (RuntimeException exception) {
                gitlabClient.deleteNote(projectId, mergeRequestIid, discussionId);
                failed++;
            }
        }
        return failed;
    }

    private long postOne(long projectId, long mergeRequestIid, String headSha, Reviewer.CommentDraft draft) {
        if (draft.newLine() != null || draft.oldLine() != null) {
            return gitlabClient.createLineDiscussion(
                    projectId,
                    mergeRequestIid,
                    draft.path(),
                    draft.newLine(),
                    draft.oldLine(),
                    headSha,
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
                    gitlabClient.listReplies(projectId, mergeRequestIid, discussionId)));
        }
        return prior;
    }

    /** 仍开着的同一问题，以及作者已表明无需修改的问题，不再另开讨论。行号变化不算新问题。 */
    private static List<Reviewer.CommentDraft> stillOpen(List<Reviewer.CommentDraft> drafts,
                                                         List<FindingEntity> existing,
                                                         List<Reviewer.PriorFinding> priorFindings,
                                                         List<Long> resolved) {
        List<Reviewer.CommentDraft> kept = new ArrayList<>();
        for (Reviewer.CommentDraft draft : drafts) {
            if (!repeatsOpenOrDeclined(draft, existing, priorFindings, resolved)) {
                kept.add(draft);
            }
        }
        return kept;
    }

    private static boolean repeatsOpenOrDeclined(Reviewer.CommentDraft draft,
                                                 List<FindingEntity> existing,
                                                 List<Reviewer.PriorFinding> priorFindings,
                                                 List<Long> resolved) {
        for (FindingEntity finding : existing) {
            if (finding.getDiscussionId() == null || resolved.contains(finding.getDiscussionId())) {
                continue;
            }
            if (!samePathAndCategory(draft, finding)) {
                continue;
            }
            if (sameBody(draft, finding) || declined(priorFindings, finding.getDiscussionId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean samePathAndCategory(Reviewer.CommentDraft draft, FindingEntity finding) {
        return java.util.Objects.equals(draft.path(), finding.getPath())
                && java.util.Objects.equals(draft.category(), finding.getCategory());
    }

    private static boolean sameBody(Reviewer.CommentDraft draft, FindingEntity finding) {
        return java.util.Objects.equals(draft.commentBody(), finding.getCommentBody());
    }

    private static boolean declined(List<Reviewer.PriorFinding> priorFindings, long discussionId) {
        for (Reviewer.PriorFinding prior : priorFindings) {
            if (prior.discussionId() != discussionId || prior.replies() == null) {
                continue;
            }
            for (String reply : prior.replies()) {
                if (reply != null && reply.contains("不需要修改")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 只把这次 diff 消除的、且本来就存在的讨论标成已解决。不删除发现，也不删讨论。 */
    private void resolveKnownDiscussions(long projectId,
                                         long mergeRequestIid,
                                         List<FindingEntity> existing,
                                         List<Reviewer.PriorFinding> priorFindings,
                                         List<Long> resolved) {
        for (Long discussionId : resolved) {
            if (discussionId == null || declined(priorFindings, discussionId)) {
                continue;
            }
            boolean known = existing.stream().anyMatch(finding -> discussionId.equals(finding.getDiscussionId()));
            if (known) {
                gitlabClient.resolveDiscussion(projectId, mergeRequestIid, discussionId);
            }
        }
    }

    private Reviewer.CodingStandards loadStandards(long projectId) {
        String configured = standardsBranch.getBranch();
        String ref = configured == null || configured.isBlank()
                ? gitlabClient.defaultBranch(projectId)
                : configured;
        java.util.ArrayList<Reviewer.StandardDocument> documents = new java.util.ArrayList<>();
        java.util.List<String> candidates = gitlabClient.listFiles(projectId, ref).stream()
                .filter(MergeRequestReviewServiceImpl::standardFile)
                .toList();
        for (String path : codingStandardPicker.pick(candidates)) {
            if (!standardFile(path) || documents.stream().anyMatch(document -> document.path().equals(path))) {
                continue;
            }
            documents.add(new Reviewer.StandardDocument(path, gitlabClient.readFile(projectId, path, ref)));
        }
        return new Reviewer.CodingStandards(java.util.List.copyOf(documents), true, "编码规范优先");
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
    private GitlabClient.DiffRefs waitForDiff(long projectId, long mergeRequestIid) {
        int attempts = Math.max(diffWait.getMaxAttempts(), 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleeper.sleep(diffWait.getInterval());
            }
            var ready = gitlabClient.diffRefs(projectId, mergeRequestIid);
            if (ready.isPresent()) {
                return ready.get();
            }
        }
        return null;
    }

    /** 同一合并请求只留最新一条失败概览讨论：先删旧的，再写新的，并记住新 note id。 */
    private void fail(long projectId, long mergeRequestIid, String reason, Reviewer.Spec spec, Reviewer.CodingStandards standards) {
        reviewRecordService.findFailureNoteId(projectId, mergeRequestIid).ifPresent(noteId ->
                gitlabClient.deleteNote(projectId, mergeRequestIid, noteId));
        Instant occurredAt = clock.now();
        long noteId = gitlabClient.createOverviewDiscussion(
                projectId,
                mergeRequestIid,
                failureBody(reason, occurredAt));
        reviewRecordService.recordFailure(
                reviewRecord(projectId, mergeRequestIid, reason, occurredAt, spec, standards),
                noteId);
    }

    private static ReviewRecordEntity reviewRecord(long projectId,
                                                    long mergeRequestIid,
                                                    String reason,
                                                    Instant occurredAt,
                                                    Reviewer.Spec spec,
                                                    Reviewer.CodingStandards standards) {
        ReviewRecordEntity record = new ReviewRecordEntity();
        record.setProjectId(projectId);
        record.setMergeRequestIid(mergeRequestIid);
        record.setReason(reason);
        record.setOccurredAt(occurredAt.toString());
        if (spec != null) {
            record.setSpecSkipped(spec.skipped());
            record.setSpecDocumentPaths(spec.documents().stream()
                    .map(Reviewer.SpecDocument::path)
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        if (standards != null) {
            record.setStandardDocumentPaths(standards.documents().stream()
                    .map(Reviewer.StandardDocument::path)
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        return record;
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

    /** 规范分支上可以当作编码规范的路径。lint 配置、依赖目录和生成目录不算。 */
    private static boolean standardFile(String path) {
        if (path == null || path.isBlank() || path.contains("..")) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] segments = normalized.split("/");
        String name = segments[segments.length - 1];
        if (name.equals(".golangci.yml") || name.equals(".golangci.yaml") || name.equals(".golangci.toml")) {
            return false;
        }
        for (String segment : segments) {
            if (segment.equals("vendor")
                    || segment.equals("node_modules")
                    || segment.equals(".git")
                    || segment.equals("dist")) {
                return false;
            }
        }
        return true;
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
