package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.models.entity.WorkbenchJob;
import com.agent.codereviewagent.service.MergeRequestReviewService;
import com.agent.codereviewagent.service.RepositoryProfile;
import com.agent.codereviewagent.service.RepositoryProfiles;
import com.agent.codereviewagent.service.WorkbenchJobs;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegisteredGitlabWebhookTest {
    @Test
    void newlyRegisteredRepositoryUsesAuthenticatedJobRouteInsteadOfTheLegacySynchronousPath() throws Exception {
        var review = mock(MergeRequestReviewService.class);
        var profiles = mock(RepositoryProfiles.class);
        var jobs = mock(WorkbenchJobs.class);
        var profile = new RepositoryProfile(11, "gitlab", 701, "team/test", "https://gitlab.test/team/test",
                List.of(), List.of(), List.of());
        when(profiles.find("gitlab", 701)).thenReturn(Optional.of(profile));
        when(jobs.webhook(profile, 7, "sha")).thenReturn(new WorkbenchJob(4, 11, 7, "sha", "QUEUED",
                "webhook", null, "2026-01-01T00:00:00Z", null));
        var mvc = MockMvcBuilders.standaloneSetup(new ReviewWebhookController(review, profiles, jobs, "test")).build();
        var event = post("/gitlab/webhooks/merge-requests").contentType(MediaType.APPLICATION_JSON)
                .header("X-Gitlab-Event", "Merge Request Hook").content("""
                        {"object_kind":"merge_request","project":{"id":701},
                         "object_attributes":{"action":"open","iid":7,"last_commit":{"id":"sha"}}}
                        """);
        mvc.perform(event).andExpect(status().isForbidden());
        verifyNoInteractions(jobs);
        mvc.perform(event.header("X-Gitlab-Token", "test")).andExpect(status().isAccepted());
        verify(jobs).webhook(profile, 7, "sha");
        verifyNoInteractions(review);
    }
}
