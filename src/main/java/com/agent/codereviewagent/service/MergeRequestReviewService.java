package com.agent.codereviewagent.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface MergeRequestReviewService {

    void accept(String gitlabEvent, JsonNode body);
}
