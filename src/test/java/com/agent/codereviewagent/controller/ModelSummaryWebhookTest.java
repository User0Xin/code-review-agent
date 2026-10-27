package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.RevisionClient;
import com.agent.codereviewagent.service.RevisionRecordService;
import com.agent.codereviewagent.service.SpecDocumentPicker;
import com.agent.codereviewagent.service.WorkItemClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 webhook 验证真实总结模型的指令、替换契约及失败时的外部行为。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ModelSummaryWebhookTest.Fakes.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/model-summary-test.db",
        "review.revision.new-findings-threshold=1",
        "review.revision.minimum-group-size=1",
        "review.standards.branch=dev"
})
class ModelSummaryWebhookTest {

    private static final String ORIGINAL = "Close external resources.";
    private static final String REPLACEMENT = ORIGINAL + "\nAlways close response bodies after use.";
    private static final String STANDARD = "# Standards\n\n" + ORIGINAL + "\n\nUse meaningful names.\n";

    @MockitoBean private ChatModel model;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private FindingService findings;
    @Autowired private ReviewRecordService reviews;
    @Autowired private RevisionRecordService revisions;
    @Autowired private SummaryGitlab gitlab;
    @Autowired private RecordingRevisions remote;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        gitlab.overviews.clear();
        remote.files = null;
        remote.title = null;
        remote.description = null;
        remote.target = null;
        remote.creates = 0;
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM review_failure_note");
        jdbc.update("DELETE FROM coding_standard_revision");
        jdbc.update("DELETE FROM revision_checkpoint");
    }

    @Test
    void summaryReceivesTheWholeSkillAndPreservesEnglishRulesWithChineseDescription() throws Exception {
        modelReturnsReplacement(true);

        webhook();

        assertThat(remote.creates).isEqualTo(1);
        assertThat(remote.files).containsOnlyKeys("docs/coding.md")
                .containsEntry("docs/coding.md", STANDARD.replace(ORIGINAL, REPLACEMENT));
        assertThat(remote.target).isEqualTo("dev");
        assertThat(remote.title).contains("编码规范修订");
        assertThat(remote.description).contains("docs/coding.md", "响应体释放", "依据的发现", "遗漏了响应体关闭");
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding ->
                assertThat(finding.getRevisionId()).isNotNull());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().get(0).getText())
                .isEqualTo(new ClassPathResource("skills/summary/SKILL.md").getContentAsString(StandardCharsets.UTF_8));
        assertThat(prompt.getValue().getInstructions().get(1).getText())
                .contains("summarize", "findings", "standards", ORIGINAL);
        assertThat(gitlab.overviews).containsExactly("响应体没有关闭。");
    }

    @Test
    void missingModelFieldKeepsFindingsUnconsumedAndRecordsErrorWithoutAnotherNotification() throws Exception {
        modelReturnsReplacement(false);

        webhook();

        assertThat(remote.creates).isZero();
        assertThat(revisions.active(9)).isEmpty();
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding ->
                assertThat(finding.getRevisionId()).isNull());
        assertThat(reviews.findByMergeRequest(9, 3)).singleElement().satisfies(review -> {
            assertThat(review.getOutcome()).isEqualTo("SUCCESS");
            assertThat(review.getSummaryError()).contains("总结模型返回的替换字段不符合约定");
        });
        assertThat(gitlab.overviews).containsExactly("响应体没有关闭。");
    }

    private void modelReturnsReplacement(boolean includeReason) {
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            long findingId = findings.findByMergeRequest(9, 3).getFirst().getId();
            Map<String, Object> replacement = new LinkedHashMap<>();
            replacement.put("group", "响应体释放");
            replacement.put("path", "docs/coding.md");
            replacement.put("original", ORIGINAL);
            replacement.put("replacement", REPLACEMENT);
            replacement.put("findingIds", List.of(findingId));
            if (includeReason) { replacement.put("reason", "发现遗漏了响应体关闭，补充现有资源释放约束。"); }
            String output = json.writeValueAsString(Map.of("replacements", List.of(replacement)));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        });
    }

    private void webhook() throws Exception {
        mvc.perform(post("/gitlab/webhooks/merge-requests").header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"object_kind":"merge_request","project":{"id":9},
                                "object_attributes":{"iid":3,"action":"open","source_branch":"main"}}
                                """))
                .andExpect(status().isOk());
    }

    static class SummaryGitlab implements GitlabClient {
        final List<String> overviews = new ArrayList<>();
        @Override public Optional<DiffRefs> diffRefs(long project, long iid) { return Optional.of(new DiffRefs("head")); }
        @Override public long createOverviewDiscussion(long project, long iid, String body) {
            overviews.add(body);
            return overviews.size();
        }
        @Override public long createLineDiscussion(long project, long iid, String path, Integer added,
                                                  Integer removed, String head, String body) { return 100; }
        @Override public void deleteNote(long project, long iid, long note) { }
        @Override public List<String> listReplies(long project, long iid, long discussion) { return List.of(); }
        @Override public void resolveDiscussion(long project, long iid, long discussion) { }
        @Override public List<String> listFiles(long project, String ref) { return List.of("docs/coding.md"); }
        @Override public String readFile(long project, String path, String ref) { return STANDARD; }
        @Override public String defaultBranch(long project) { return "main"; }
    }

    static class RecordingRevisions implements RevisionClient {
        int creates;
        Map<String, String> files;
        String title;
        String description;
        String target;
        @Override public Optional<RemoteRevision> findBySourceBranch(long project, String branch) { return Optional.empty(); }
        @Override public RemoteRevision createOrRecover(long project, String source, String target,
                                                       Map<String, String> files, String title, String description) {
            creates++;
            this.files = Map.copyOf(files);
            this.title = title;
            this.description = description;
            this.target = target;
            return new RemoteRevision(901, "opened");
        }
        @Override public RemoteRevision get(long project, long iid) { return new RemoteRevision(iid, "opened"); }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary SummaryGitlab summaryGitlab() { return new SummaryGitlab(); }
        @Bean @Primary RecordingRevisions summaryRemoteRevisions() { return new RecordingRevisions(); }
        @Bean @Primary Reviewer summaryReviewer() {
            return request -> Reviewer.ReviewFindings.of(List.of(
                    new Reviewer.CommentDraft("main.go", null, null, "缺陷", null, "响应体没有关闭。")));
        }
        @Bean @Primary WorkItemClient summaryWorkItems() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker summarySpecPicker() { return paths -> List.of(); }
        @Bean @Primary CodingStandardPicker summaryStandardsPicker() { return paths -> List.of("docs/coding.md"); }
    }
}
