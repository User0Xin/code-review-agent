package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.config.StandardsBranchProperties;
import com.agent.codereviewagent.models.entity.FindingEntity;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.service.FindingService;
import com.agent.codereviewagent.service.GitlabClient;
import com.agent.codereviewagent.service.ReviewRecordService;
import com.agent.codereviewagent.service.WorkItemClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 webhook 覆盖真实模型适配器、指令、路径校验、规范读取和评论写回。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ModelReviewWebhookTest.Fakes.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:target/model-review-test.db")
class ModelReviewWebhookTest {

    private static final String DIFF = "diff --git a/main.go b/main.go\n@@ -7,1 +8,1 @@\n-oldName\n+newName";
    private static final String COMMENT = "请遵循当前规范，变量名应体现用途。";

    @MockitoBean private ChatModel model;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ReviewGitlab gitlab;
    @Autowired private StandardsBranchProperties standards;
    @Autowired private FindingService findings;
    @Autowired private ReviewRecordService records;
    @Autowired private JdbcTemplate jdbc;

    private final List<Called> calls = new ArrayList<>();
    private List<String> selectedStandards;
    private List<String> selectedSpec;

    @BeforeEach
    void prepare() {
        standards.setBranch("dev");
        gitlab.reads.clear();
        gitlab.comments.clear();
        calls.clear();
        selectedStandards = List.of("docs/coding.md", ".golangci.yml", "eslint.config.js", "docs/missing.md");
        selectedSpec = List.of("docs/design.md", "docs/coding.md", "docs/missing.md", "docs/vendor/hidden.md");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM review_failure_note");
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            JsonNode request = json.readTree(prompt.getUserMessage().getText());
            String task = request.path("task").asText();
            calls.add(new Called(task, request.path("input"), prompt.getSystemMessage().getText()));
            String answer = switch (task) {
                case "select_coding_standards" -> json.writeValueAsString(Map.of("paths", selectedStandards));
                case "select_spec_documents" -> json.writeValueAsString(Map.of("paths", selectedSpec));
                case "review" -> """
                        {"drafts":[{"path":"main.go","newLine":8,"oldLine":null,
                        "category":"编码规范","citation":"%s","commentBody":"%s"}],
                        "resolvedDiscussionIds":[]}
                        """.formatted(selectedStandards.isEmpty() ? "通用坏味道：神秘命名" : "docs/coding.md：命名", COMMENT);
                default -> throw new AssertionError("未知模型任务：" + task);
            };
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
    }

    @Test
    void modelReviewsTheActualDiffAgainstTheEffectiveStandardAndPostsItsFinding() throws Exception {
        webhook();

        assertThat(gitlab.reads).containsExactly("dev:docs/coding.md", "head:docs/design.md");
        assertThat(calls).extracting(Called::task)
                .containsExactly("select_coding_standards", "select_spec_documents", "review");
        Called review = call("review");
        assertThat(review.input().path("diff").asText()).isEqualTo(DIFF);
        assertThat(review.input().path("codingStandards").path("documents").get(0).path("content").asText())
                .isEqualTo("当前规范：名称必须说明用途");
        assertThat(review.input().toString()).doesNotContain("合并请求里尚未生效的规范");
        assertThat(review.input().path("spec").path("documents").get(0).path("content").asText())
                .isEqualTo("本次功能应保留导出字段");
        assertThat(call("select_coding_standards").input().toString())
                .doesNotContain(".golangci.yml", "eslint.config.js", "vendor/hidden.md");
        assertThat(call("select_spec_documents").input().toString())
                .doesNotContain("docs/coding.md", "docs/vendor/hidden.md");
        assertThat(gitlab.comments).containsExactly(new Posted("main.go", 8, "head", COMMENT));
        assertThat(findings.findByMergeRequest(9, 3)).singleElement().satisfies(finding -> {
            assertThat(finding.getCategory()).isEqualTo("编码规范");
            assertThat(finding.getHeadSha()).isEqualTo("head");
            assertThat(finding.getCommentBody()).isEqualTo(COMMENT);
        });
        assertThat(records.findByMergeRequest(9, 3)).singleElement().satisfies(record -> {
            assertThat(record.getOutcome()).isEqualTo("SUCCESS");
            assertThat(record.getStandardDocumentPaths()).isEqualTo("docs/coding.md");
            assertThat(record.getSpecDocumentPaths()).isEqualTo("docs/design.md");
        });
        assertWholeInstructionWasInjected();
    }

    @Test
    void selectingNoDocumentsStillReviewsWithSmellsAndSilentlySkipsSpec() throws Exception {
        selectedStandards = List.of();
        selectedSpec = List.of();

        webhook();

        Called review = call("review");
        assertThat(review.input().path("codingStandards").path("documents")).isEmpty();
        assertThat(review.input().path("codingStandards").path("smellsApply").asBoolean()).isTrue();
        assertThat(review.input().path("codingStandards").path("precedence").asText()).contains("编码规范优先");
        assertThat(review.input().path("spec").path("skipped").asBoolean()).isTrue();
        assertThat(gitlab.reads).isEmpty();
        assertThat(gitlab.comments).singleElement().extracting(Posted::body).isEqualTo(COMMENT);
        assertThat(findings.findByMergeRequest(9, 3)).singleElement()
                .extracting(FindingEntity::getCommentBody).isEqualTo(COMMENT);
        assertThat(records.findByMergeRequest(9, 3)).singleElement()
                .extracting(ReviewRecordEntity::getSpecSkipped).isEqualTo(true);
        assertWholeInstructionWasInjected();
    }

    private Called call(String task) {
        return calls.stream().filter(call -> call.task().equals(task)).findFirst().orElseThrow();
    }

    private void assertWholeInstructionWasInjected() throws Exception {
        String instruction = new ClassPathResource("skills/review/SKILL.md").getContentAsString(StandardCharsets.UTF_8);
        assertThat(calls).allSatisfy(call -> assertThat(call.system()).isEqualTo(instruction));
        assertThat(instruction).contains("name: merge-request-review", "通用坏味道", "编码规范", "优先");
    }

    private void webhook() throws Exception {
        mvc.perform(post("/gitlab/webhooks/merge-requests").header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"object_kind":"merge_request","project":{"id":9},"object_attributes":
                                  {"iid":3,"action":"open","source_branch":"main","description":"只提供补充背景"}}
                                """))
                .andExpect(status().isOk());
    }

    record Called(String task, JsonNode input, String system) { }
    record Posted(String path, Integer newLine, String head, String body) { }

    static class ReviewGitlab implements GitlabClient {
        final List<String> reads = new ArrayList<>();
        final List<Posted> comments = new ArrayList<>();

        @Override public Optional<DiffRefs> diffRefs(long project, long iid) { return Optional.of(new DiffRefs("head")); }
        @Override public String readDiff(long project, long iid, String head) { return DIFF; }
        @Override public long createOverviewDiscussion(long project, long iid, String body) {
            comments.add(new Posted(null, null, null, body));
            return 200 + comments.size();
        }
        @Override public long createLineDiscussion(long project, long iid, String path, Integer newLine,
                                                   Integer oldLine, String head, String body) {
            comments.add(new Posted(path, newLine, head, body));
            return 200 + comments.size();
        }
        @Override public void deleteNote(long project, long iid, long note) { }
        @Override public List<String> listReplies(long project, long iid, long discussion) { return List.of(); }
        @Override public void resolveDiscussion(long project, long iid, long discussion) { }
        @Override public List<String> listFiles(long project, String ref) {
            return ref.equals("head")
                    ? List.of("docs/coding.md", "docs/design.md", "docs/vendor/hidden.md")
                    : List.of("docs/coding.md", ".golangci.yml", "eslint.config.js", "vendor/hidden.md");
        }
        @Override public String readFile(long project, String path, String ref) {
            reads.add(ref + ":" + path);
            if (path.equals("docs/coding.md")) {
                return ref.equals("dev") ? "当前规范：名称必须说明用途" : "合并请求里尚未生效的规范";
            }
            if (path.equals("docs/design.md") && ref.equals("head")) { return "本次功能应保留导出字段"; }
            throw new AssertionError("不应读取候选范围外文件：" + ref + ":" + path);
        }
        @Override public String defaultBranch(long project) { return "main"; }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ReviewGitlab modelReviewGitlab() { return new ReviewGitlab(); }
        @Bean @Primary WorkItemClient modelReviewWorkItems() { return id -> Optional.empty(); }
    }
}
