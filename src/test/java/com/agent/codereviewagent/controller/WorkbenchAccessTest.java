package com.agent.codereviewagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "review.workbench.token=local-test"})
class WorkbenchAccessTest {
    @Autowired private MockMvc mvc;

    @Test
    void bothReadingAndChangingSettingsRequireTheSeparateWorkbenchCredential() throws Exception {
        mvc.perform(get("/api/repositories")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/repositories/42/skills/review")
                        .header("Authorization", "Bearer wrong").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/repositories").header("Authorization", "Bearer local-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
