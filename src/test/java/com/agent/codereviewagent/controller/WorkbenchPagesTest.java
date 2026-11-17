package com.agent.codereviewagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "review.workbench.token=page-test"})
@AutoConfigureMockMvc
class WorkbenchPagesTest {
    @Autowired MockMvc mvc;

    @Test
    void onlyKnownPagePathsForwardToTheBundle() throws Exception {
        for (String path : new String[]{"/repositories", "/repositories/", "/reviews", "/reviews/"}) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
            mvc.perform(post(path)).andExpect(status().isMethodNotAllowed());
        }
        for (String path : new String[]{"/unknown", "/repositories/missing.js", "/assets/missing.js",
                "/api/unknown", "/github/webhooks/unknown", "/gitlab/webhooks/unknown"}) {
            mvc.perform(get(path).header("Authorization", "Bearer page-test"))
                    .andExpect(status().isNotFound()).andExpect(forwardedUrl(null));
        }
    }

    @Test
    void pagesDoNotBypassApiAuthorization() throws Exception {
        mvc.perform(get("/api/repositories")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/unknown")).andExpect(status().isUnauthorized());
    }
}
