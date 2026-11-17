package com.agent.codereviewagent.integration.model;

import com.agent.codereviewagent.service.*;

import com.agent.codereviewagent.models.dto.RepositoryRequest;
import com.agent.codereviewagent.models.dto.SkillImportRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 经真实仓库配置和 SQLite，断言最终送给模型的指令、规范、spec 以及提交。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "review.diff-wait.max-attempts=1"})
class RepositoryProfilesTest {
    @Autowired private RepositoryProfiles profiles;
    @Autowired private MergeRequestReviewService review;
    @Autowired private ObjectMapper json;
    @MockitoBean(name="githubClient") private RepositoryClient github;
    @MockitoBean private ChatModel model;

    @Test
    void usesEachRepositorysOwnSkillAndDocumentsAtTheirPinnedRefs() {
        var a = repository(101L,"alpha");
        var b = repository(102L,"beta");
        var refs = new RepositoryClient.DiffRefs("head","base",null);
        List<String> instructions = new ArrayList<>();
        List<JsonNode> inputs = new ArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            instructions.add(prompt.getSystemMessage().getText());
            var body = json.readTree(prompt.getUserMessage().getText());
            assertThat(body.path("task").asText()).isEqualTo("review");
            inputs.add(body.path("input"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                    {"drafts":[],"resolvedDiscussionIds":[],"declinedDiscussionIds":[]}
                    """))));
        });
        for (var repo : List.of(a,b)) {
            var run = review.runConfigured(repo.id(), 7, "manual", repo.fullName(), refs);
            assertThat(run.getOutcome()).isEqualTo("SUCCESS");
            assertThat(run.getId()).isPositive();
            assertThat(run.getProjectId()).isEqualTo(-repo.remoteId());
        }
        assertThat(instructions).containsExactly("alpha-only instruction","beta-only instruction");
        for (int i=0;i<2;i++) {
            String name = i==0 ? "alpha" : "beta";
            assertThat(inputs.get(i).path("codingStandards").path("documents").get(0).path("content").asText())
                    .isEqualTo(name + "-rules@stable");
            assertThat(inputs.get(i).path("spec").path("documents").get(0).path("content").asText())
                    .isEqualTo(name + "-spec@head");
        }
        profiles.storeSkill(a.id(),"review",new SkillImportRequest(null,false));
        assertThat(profiles.skill(a.id(),"review").content()).isEqualTo("alpha-only instruction");
        review.runConfigured(a.id(),7,"manual","",refs);
        assertThat(instructions.getLast()).isEqualTo(RepositoryProfiles.defaultSkill("review"));
        assertThat(profiles.skill(b.id(),"review").enabled()).isTrue();
        assertThat(ReviewPolicy.current()).isEmpty();
        // 人工指定旧的 base，即便源分支 head 未变也不得把旧版本送去审查。
        var old = review.runConfigured(b.id(),7,"manual","",new RepositoryClient.DiffRefs("head","old-base",null));
        assertThat(old.getOutcome()).isEqualTo("FAILED");
        assertThat(inputs).hasSize(3);
    }

    private RepositoryProfile repository(long remoteId,String name) {
        String fullName = "owner/" + name;
        when(github.resolveId(fullName)).thenReturn(remoteId);
        var repo = profiles.create(new RepositoryRequest("https://github.com/" + fullName,List.of("rules/" + name),
                List.of("requests/" + name + ".md")));
        profiles.storeSkill(repo.id(),"review",new SkillImportRequest(name + "-only instruction",true));
        when(github.bound(remoteId,fullName)).thenReturn(github);
        var refs = new RepositoryClient.DiffRefs("head","base",null);
        when(github.diffRefs(remoteId,7)).thenReturn(Optional.of(refs));
        when(github.defaultBranch(remoteId)).thenReturn("main");
        when(github.branchHead(remoteId,"main")).thenReturn("stable");
        String rules = "rules/" + name + "/README.md";
        String spec = "requests/" + name + ".md";
        when(github.listFiles(remoteId,"stable")).thenReturn(List.of(rules,"rules/" + name + "-other/rules.md"));
        when(github.listFiles(remoteId,"head")).thenReturn(List.of(spec));
        when(github.readFile(remoteId,rules,"stable")).thenReturn(name + "-rules@stable");
        when(github.readFile(remoteId,spec,"head")).thenReturn(name + "-spec@head");
        when(github.readDiff(remoteId,7,refs)).thenReturn("diff-" + name);
        return repo;
    }

    @Test
    void duplicateRegistrationDoesNotOverwriteRulesAndBadImportKeepsSavedSkill() {
        var repo = repository(103L,"gamma");
        assertThatThrownBy(() -> profiles.create(new RepositoryRequest(repo.url(),List.of("other"),List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已存在");
        assertThat(profiles.get(repo.id()).standardPaths()).containsExactly("rules/gamma");
        assertThatThrownBy(() -> profiles.storeSkill(repo.id(),"review",new SkillImportRequest("字".repeat(22000),true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(profiles.skill(repo.id(),"review").content()).isEqualTo("gamma-only instruction");
    }

    @Test
    void rejectsUntrustedHostsAndUnsafePathsBeforeContactingPlatform() {
        for (String url : List.of("https://not-github.test/x/y","https://github.com@another.test/x/y",
                "https://github.com/x/../y","https://github.com/o/r?q=token","https://github.com:444/o/r")) {
            assertThatThrownBy(() -> profiles.create(new RepositoryRequest(url,List.of(),List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> profiles.create(new RepositoryRequest("https://github.com/o/r",List.of("../rules"),List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(github);
    }
}
