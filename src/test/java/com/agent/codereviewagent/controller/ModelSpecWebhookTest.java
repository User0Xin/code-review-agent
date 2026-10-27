package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.CodingStandardPicker;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.Reviewer;
import com.agent.codereviewagent.service.WorkItemClient;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 模型保持为 ChatModel 替身；从 webhook 验证真实指令加载、路径选择和读取。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ModelSpecWebhookTest.Fakes.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/model-spec-test.db")
class ModelSpecWebhookTest {

    @MockitoBean private ChatModel model;
    @Autowired private MockMvc mvc;
    @Autowired private SpecGitlab gitlab;
    @Autowired private RecordingReviewer reviewer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReviewRecordService records;

    @BeforeEach
    void reset() {
        gitlab.files = List.of("docs/design.md", "docs/vendor/hidden.md", "src/private.md");
        gitlab.reads.clear();
        gitlab.overviews.clear();
        reviewer.request = null;
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM review_failure_note");
    }

    @Test
    void modelChoosesOnlyExistingSpecCandidatesAndTheWholeInstructionIsLoaded() throws Exception {
        answer("{\"paths\":[\"docs/design.md\",\"docs/nonexistent.md\",\"docs/vendor/hidden.md\"]}");

        webhook();

        assertThat(gitlab.reads).containsExactly("head:docs/design.md");
        assertThat(reviewer.request.spec().documents()).singleElement()
                .extracting(Reviewer.SpecDocument::content).isEqualTo("本次功能契约");
        assertThat(records.findByMergeRequest(9, 3)).singleElement()
                .extracting(ReviewRecordEntity::getSpecDocumentPaths).isEqualTo("docs/design.md");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().get(0).getText())
                .isEqualTo(new ClassPathResource("skills/review/SKILL.md").getContentAsString(StandardCharsets.UTF_8))
                .contains("name: merge-request-review", "select_spec_documents", "description:");
        assertThat(prompt.getValue().getInstructions().get(1).getText())
                .contains("docs/design.md").doesNotContain("docs/vendor/hidden.md", "src/private.md");
    }

    @Test
    void invalidModelJsonLeavesAFailureDiscussionWithoutReadingDocuments() throws Exception {
        assertSelectionFailure("{\"paths\":[\"docs/design.md\"]} {\"extra\":true}");
    }

    @Test
    void nullPathsLeaveAFailureDiscussionWithoutCallingTheReviewer() throws Exception {
        assertSelectionFailure("{\"paths\":null}");
    }

    private void assertSelectionFailure(String response) throws Exception {
        answer(response);

        webhook();

        assertThat(gitlab.reads).isEmpty();
        assertThat(reviewer.request).isNull();
        assertThat(gitlab.overviews).singleElement().asString().contains("spec 读取失败");
        assertThat(records.findByMergeRequest(9, 3)).singleElement().satisfies(record -> {
            assertThat(record.getOutcome()).isEqualTo("FAILED");
            assertThat(record.getReason()).isEqualTo("spec 读取失败");
        });
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

    static class SpecGitlab implements GitlabClient {
        List<String> files;
        final List<String> reads = new ArrayList<>();
        final List<String> overviews = new ArrayList<>();
        @Override public Optional<DiffRefs> diffRefs(long project, long iid) { return Optional.of(new DiffRefs("head")); }
        @Override public long createOverviewDiscussion(long project, long iid, String body) {
            overviews.add(body);
            return 100;
        }
        @Override public long createLineDiscussion(long project, long iid, String path, Integer newLine,
                                                  Integer oldLine, String head, String body) { return 101; }
        @Override public void deleteNote(long project, long iid, long note) { }
        @Override public List<String> listReplies(long project, long iid, long discussion) { return List.of(); }
        @Override public void resolveDiscussion(long project, long iid, long discussion) { }
        @Override public List<String> listFiles(long project, String ref) { return ref.equals("head") ? files : List.of(); }
        @Override public String readFile(long project, String path, String ref) {
            reads.add(ref + ":" + path);
            return "本次功能契约";
        }
        @Override public String defaultBranch(long project) { return "main"; }
    }

    static class RecordingReviewer implements Reviewer {
        ReviewRequest request;
        @Override public ReviewFindings review(ReviewRequest request) {
            this.request = request;
            return ReviewFindings.none();
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary SpecGitlab modelSpecGitlab() { return new SpecGitlab(); }
        @Bean @Primary RecordingReviewer modelSpecReviewer() { return new RecordingReviewer(); }
        @Bean @Primary WorkItemClient modelSpecWorkItems() { return id -> Optional.empty(); }
        @Bean @Primary CodingStandardPicker modelSpecStandards() { return paths -> List.of(); }
    }
}
