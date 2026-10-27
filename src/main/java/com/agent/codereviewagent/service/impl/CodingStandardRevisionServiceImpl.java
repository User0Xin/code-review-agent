package com.agent.codereviewagent.service.impl;

import com.agent.codereviewagent.config.RevisionProperties;
import com.agent.codereviewagent.models.entity.CodingStandardRevisionEntity;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** 先持久化精确提案，再以同一源分支创建或恢复远端修订。 */
@Service
public class CodingStandardRevisionServiceImpl implements CodingStandardRevisionService {
    private static final Logger LOG = LoggerFactory.getLogger(CodingStandardRevisionServiceImpl.class);
    private final FindingService findings;
    private final RevisionRecordService records;
    private final ReviewRecordService reviews;
    private final CodingStandardSource standards;
    private final RevisionSummarizer summarizer;
    private final RevisionClient client;
    private final RevisionProperties properties;
    private final ObjectMapper json;

    public CodingStandardRevisionServiceImpl(FindingService findings, RevisionRecordService records,
            ReviewRecordService reviews, CodingStandardSource standards, RevisionSummarizer summarizer,
            RevisionClient client, RevisionProperties properties, ObjectMapper json) {
        this.findings = findings;
        this.records = records;
        this.reviews = reviews;
        this.standards = standards;
        this.summarizer = summarizer;
        this.client = client;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public synchronized void checkAfterReview(long projectId, long mergeRequestIid) {
        try {
            check(projectId);
        }
        catch (RuntimeException exception) {
            // 不发布额外通知；重试进度与审查记录持久化，下一次结束时重试。
            var checkpoint = records.checkpoint(projectId);
            records.checkpoint(projectId, checkpoint.getLastFindingId(), true);
            String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            records.active(projectId).ifPresent(revision -> {
                revision.setError(error);
                records.saveState(revision);
            });
            reviews.recordRevisionFailure(projectId, mergeRequestIid, error);
            LOG.warn("项目 {} 的编码规范修订失败：{}", projectId, error);
        }
    }

    private void check(long projectId) {
        var active = records.active(projectId);
        if (active.isPresent()) {
            CodingStandardRevisionEntity revision = active.get();
            if ("PREPARING".equals(revision.getState())) {
                publish(revision);
                return;
            }
            var remote = client.get(projectId, revision.getMergeRequestIid());
            records.complete(revision, remote);
            if (List.of("opened", "locked", "OPEN").contains(remote.state())) { return; }
        }
        List<FindingEntity> candidates = findings.findUnconsumed(projectId);
        var checkpoint = records.checkpoint(projectId);
        long newCount = candidates.stream().filter(f -> f.getId() > checkpoint.getLastFindingId()).count();
        if (candidates.isEmpty() || (!Boolean.TRUE.equals(checkpoint.getRetryPending())
                && newCount < properties.getNewFindingsThreshold())) { return; }
        var snapshot = standards.load(projectId);
        if (snapshot.documents().isEmpty()) { return; }
        records.checkpoint(projectId, checkpoint.getLastFindingId(), true);
        var result = summarizer.summarize(candidates, snapshot.documents());
        Proposal proposal = validate(result, candidates, snapshot.documents());
        long last = candidates.stream().mapToLong(FindingEntity::getId).max().orElse(checkpoint.getLastFindingId());
        if (proposal.files().isEmpty()) {
            records.checkpoint(projectId, last, proposal.rejected());
            return;
        }
        CodingStandardRevisionEntity revision = new CodingStandardRevisionEntity();
        revision.setProjectId(projectId);
        revision.setSourceBranch("code-review/standards-" + UUID.randomUUID());
        revision.setTargetBranch(snapshot.branch());
        revision.setBaseRef(snapshot.ref());
        revision.setState("PREPARING");
        revision.setFilesJson(encode(proposal.files()));
        revision.setFindingIds(proposal.findingIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        revision.setTitle("编码规范修订：归纳审查发现");
        revision.setDescription(proposal.description());
        records.prepare(revision);
        records.checkpoint(projectId, last, proposal.rejected());
        publish(revision);
    }

    private void publish(CodingStandardRevisionEntity revision) {
        Map<String, String> files;
        try { files = json.readValue(revision.getFilesJson(), new TypeReference<LinkedHashMap<String, String>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("已保存修订内容无法读取"); }
        var remote = client.createOrRecover(revision.getProjectId(), revision.getSourceBranch(), revision.getTargetBranch(),
                revision.getBaseRef() == null ? revision.getTargetBranch() : revision.getBaseRef(),
                files, revision.getTitle(), revision.getDescription());
        records.complete(revision, remote);
    }

    private Proposal validate(RevisionSummarizer.Summary summary, List<FindingEntity> candidates,
                              List<Reviewer.StandardDocument> documents) {
        if (summary == null || summary.replacements() == null) { throw new IllegalStateException("总结缺少替换片段"); }
        Set<Long> eligible = candidates.stream().map(FindingEntity::getId).collect(Collectors.toSet());
        Map<String, String> contents = documents.stream().collect(Collectors.toMap(Reviewer.StandardDocument::path,
                Reviewer.StandardDocument::content, (first, ignored) -> first, LinkedHashMap::new));
        List<RevisionSummarizer.Replacement> classified = summary.replacements().stream()
                .filter(r -> r != null && r.group() != null && !r.group().isBlank()
                        && r.findingIds() != null && !r.findingIds().isEmpty() && eligible.containsAll(r.findingIds())).toList();
        Map<Long, Set<String>> owners = new HashMap<>();
        classified.forEach(r -> r.findingIds().forEach(id -> owners.computeIfAbsent(id, ignored -> new HashSet<>()).add(r.group())));
        classified = classified.stream().filter(r -> r.findingIds().stream().allMatch(id -> owners.get(id).size() == 1)).toList();
        Map<String, Set<Long>> groups = new HashMap<>();
        classified.forEach(r -> groups.computeIfAbsent(r.group(), ignored -> new HashSet<>()).addAll(r.findingIds()));
        Map<String, List<Patch>> patches = new LinkedHashMap<>();
        Set<Long> used = new HashSet<>();
        StringBuilder description = new StringBuilder("根据已写回的审查发现修订现有编码规范，供维护者审核。\n");
        boolean rejected = classified.size() != summary.replacements().size();
        for (var r : classified) {
            if (groups.get(r.group()).size() < properties.getMinimumGroupSize()) { continue; }
            String content = contents.get(r.path());
            int start = content == null || r.original() == null || r.original().isEmpty() ? -1 : content.indexOf(r.original());
            if (start < 0 || r.replacement() == null || r.replacement().isBlank() || r.reason() == null || r.reason().isBlank()
                    || content.indexOf(r.original(), start + 1) >= 0 || r.original().equals(r.replacement())
                    || (r.original().equals(content) && !r.replacement().startsWith(content))) {
                rejected = true;
                continue;
            }
            int end = start + r.original().length();
            List<Patch> filePatches = patches.computeIfAbsent(r.path(), ignored -> new ArrayList<>());
            if (filePatches.stream().anyMatch(p -> start < p.end() && end > p.start())) {
                rejected = true;
                continue;
            }
            filePatches.add(new Patch(start, end, r.replacement()));
            used.addAll(r.findingIds());
            description.append("\n- 文件：").append(r.path()).append("\n  改动：").append(r.group())
                    .append("\n  理由：").append(r.reason()).append("\n  依据的发现：").append(r.findingIds()).append('\n');
        }
        Map<String, String> files = new LinkedHashMap<>();
        patches.forEach((path, changes) -> {
            if (changes.isEmpty()) { return; }
            StringBuilder content = new StringBuilder(contents.get(path));
            changes.stream().sorted(Comparator.comparingInt(Patch::start).reversed())
                    .forEach(p -> content.replace(p.start(), p.end(), p.text()));
            files.put(path, content.toString());
        });
        return new Proposal(files, used, description.toString(), rejected);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法保存编码规范修订"); }
    }
    private record Patch(int start, int end, String text) { }
    private record Proposal(Map<String, String> files, Set<Long> findingIds, String description, boolean rejected) { }
}
