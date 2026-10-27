package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.RevisionClient;
import com.agent.codereviewagent.service.RevisionSummarizer;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 webhook 使用真实误报模型适配器，验证最终判定如何影响修订。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ModelFalsePositiveWebhookTest.Fakes.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/model-false-positive-test.db",
        "review.revision.new-findings-threshold=1",
        "review.revision.minimum-group-size=1"
})
class ModelFalsePositiveWebhookTest {

    @MockitoBean private ChatModel model;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private FindingService findings;
    @Autowired private ReviewRecordService reviews;
    @Autowired private ReplyGitlab gitlab;
    @Autowired private RevisionRemote remote;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        gitlab.replies = List.of();
        gitlab.overviews.clear();
        remote.creates = 0;
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM review_failure_note");
        jdbc.update("DELETE FROM coding_standard_revision");
        jdbc.update("DELETE FROM revision_checkpoint");
    }

    @Test
    void fullSkillAndAllRepliesEnterTheJudgeAndFinalAcceptanceAllowsARevision() throws Exception {
        gitlab.replies = List.of("我认为不需要修改。", "确认遗漏释放，需要修改，已修复。");
        answer("{\"falsePositive\":false,\"reason\":\"最终确认需要释放资源，仍认可该发现。\"}");

        webhook();

        assertThat(remote.creates).isEqualTo(1);
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding -> {
            assertThat(finding.getFalsePositive()).isFalse();
            assertThat(finding.getFalsePositiveReason()).isEqualTo("最终确认需要释放资源，仍认可该发现。");
            assertThat(finding.getRevisionId()).isNotNull();
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().get(0).getText())
                .isEqualTo(new ClassPathResource("skills/false-positive/SKILL.md").getContentAsString(StandardCharsets.UTF_8));
        var input = json.readTree(prompt.getValue().getInstructions().get(1).getText());
        assertThat(input.path("task").asText()).isEqualTo("judge_false_positive");
        assertThat(input.path("input").path("replies")).isEqualTo(json.valueToTree(gitlab.replies));
        assertThat(input.path("input").path("finding").path("commentBody").asText()).isEqualTo("资源没有释放。");
    }

    @Test
    void finalRejectionIsStoredAndPreventsTheFindingFromEnteringARevision() throws Exception {
        gitlab.replies = List.of("我会修改。", "核对后确认由调用者释放，无需修改这里。");
        answer("{\"falsePositive\":true,\"reason\":\"最终确认由调用者释放，此处无需修改。\"}");

        webhook();

        assertThat(remote.creates).isZero();
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding -> {
            assertThat(finding.getFalsePositive()).isTrue();
            assertThat(finding.getFalsePositiveReason()).isEqualTo("最终确认由调用者释放，此处无需修改。");
            assertThat(finding.getRevisionId()).isNull();
        });
        assertThat(gitlab.overviews).containsExactly("资源没有释放。");
    }

    @Test
    void noExternalRepliesCannotBeUsedAsEvidenceOfAFalsePositive() throws Exception {
        answer("{\"falsePositive\":true,\"reason\":\"没有回复。\"}");

        webhook();

        assertThat(remote.creates).isEqualTo(1);
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding ->
                assertThat(finding.getFalsePositive()).isFalse());
    }

    @Test
    void aMissingBooleanLeavesTheFindingUnconsumedAndRecordsASummaryError() throws Exception {
        gitlab.replies = List.of("请核对所有权。");
        answer("{\"reason\":\"尚未明确是否需要修改。\"}");

        webhook();

        assertThat(remote.creates).isZero();
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding ->
                assertThat(finding.getRevisionId()).isNull());
        assertThat(reviews.findByMergeRequest(9, 3)).singleElement().satisfies(review ->
                assertThat(review.getSummaryError()).contains("误报模型返回的判定字段不符合约定"));
        assertThat(gitlab.overviews).containsExactly("资源没有释放。");
    }

    private void answer(String text) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private void webhook() throws Exception {
        mvc.perform(post("/gitlab/webhooks/merge-requests").header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"object_kind":"merge_request","project":{"id":9},
                                "object_attributes":{"iid":3,"action":"open","source_branch":"main"}}
                                """))
                .andExpect(status().isOk());
    }

    static class ReplyGitlab implements GitlabClient {
        List<String> replies = List.of();
        final List<String> overviews = new ArrayList<>();
        @Override public Optional<DiffRefs> diffRefs(long project, long iid) { return Optional.of(new DiffRefs("head")); }
        @Override public long createOverviewDiscussion(long project, long iid, String body) {
            overviews.add(body);
            return overviews.size();
        }
        @Override public long createLineDiscussion(long project, long iid, String path, Integer added,
                                                  Integer removed, String head, String body) { return 100; }
        @Override public void deleteNote(long project, long iid, long note) { }
        @Override public List<String> listReplies(long project, long iid, long discussion) { return replies; }
        @Override public void resolveDiscussion(long project, long iid, long discussion) { }
        @Override public List<String> listFiles(long project, String ref) { return List.of("docs/coding.md"); }
        @Override public String readFile(long project, String path, String ref) { return "释放外部资源。\n\n其他约束。"; }
        @Override public String defaultBranch(long project) { return "dev"; }
    }

    static class RevisionRemote implements RevisionClient {
        int creates;
        @Override public Optional<RemoteRevision> findBySourceBranch(long project, String source) { return Optional.empty(); }
        @Override public RemoteRevision createOrRecover(long project, String source, String target, Map<String, String> files,
                                                       String title, String description) {
            creates++;
            return new RemoteRevision(901, "opened");
        }
        @Override public RemoteRevision get(long project, long iid) { return new RemoteRevision(iid, "opened"); }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ReplyGitlab falsePositiveGitlab() { return new ReplyGitlab(); }
        @Bean @Primary RevisionRemote falsePositiveRemote() { return new RevisionRemote(); }
        @Bean @Primary Reviewer falsePositiveReviewer() {
            return request -> Reviewer.ReviewFindings.of(List.of(
                    new Reviewer.CommentDraft("main.go", null, null, "缺陷", null, "资源没有释放。")));
        }
        @Bean @Primary WorkItemClient falsePositiveWorkItems() { return id -> Optional.empty(); }
        @Bean @Primary SpecDocumentPicker falsePositiveSpecPicker() { return paths -> List.of(); }
        @Bean @Primary CodingStandardPicker falsePositiveStandardsPicker() { return paths -> List.of("docs/coding.md"); }
        @Bean @Primary RevisionSummarizer falsePositiveSummarizer() {
            return (candidates, standards) -> new RevisionSummarizer.Summary(List.of(new RevisionSummarizer.Replacement(
                    "资源释放", "docs/coding.md", "释放外部资源。", "释放外部资源。\n响应体也应及时释放。",
                    candidates.stream().map(finding -> finding.getId()).toList(), "补充响应体释放约束。")));
        }
    }
}
