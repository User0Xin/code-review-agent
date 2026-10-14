package com.agent.codereviewagent.controller;


import com.agent.codereviewagent.models.dto.AgentChatRequest;
import com.agent.codereviewagent.models.dto.AgentChatResponse;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final ChatClient chatClient;
    private final ReactAgent courseAgent;

    public AiController(ChatClient chatClient, ReactAgent courseAgent) {
        this.chatClient = chatClient;
        this.courseAgent = courseAgent;
    }

    @GetMapping("/chat")
    public Map<String, String> chat(@RequestParam String message) {
        requireMessage(message);
        String answer = chatClient.prompt().user(message).call().content();
        return Map.of("answer", answer == null ? "" : answer);
    }

    @GetMapping("/agent")
    public AgentChatResponse agent(@RequestParam String message,
                                   @RequestParam(defaultValue = "default") String threadId) throws GraphRunnerException {
        return askAgent(message, threadId);
    }

    @PostMapping("/agent")
    public AgentChatResponse agent(@RequestBody AgentChatRequest request) throws GraphRunnerException {
        String threadId = request.threadId() == null || request.threadId().isBlank() ? "default" : request.threadId();
        return askAgent(request.message(), threadId);
    }

    @GetMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message,
                               @RequestParam(defaultValue = "default") String threadId) throws GraphRunnerException {
        requireMessage(message);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        return courseAgent.streamMessages(message, config)
                .filter(AiController::isVisibleAssistantText)
                .map(item -> ((AssistantMessage) item).getText());
    }

    private AgentChatResponse askAgent(String message, String threadId) throws GraphRunnerException {
        requireMessage(message);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        AssistantMessage response = courseAgent.call(message, config);
        String text = response == null || response.getText() == null ? "" : response.getText();
        return new AgentChatResponse(threadId, text);
    }

    private static void requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
    }

    private static boolean isVisibleAssistantText(Message message) {
        if (!(message instanceof AssistantMessage assistantMessage)) {
            return false;
        }
        return !assistantMessage.hasToolCalls()
                && assistantMessage.getText() != null
                && !assistantMessage.getText().isEmpty();
    }

}
