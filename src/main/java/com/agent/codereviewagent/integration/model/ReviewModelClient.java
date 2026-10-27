package com.agent.codereviewagent.integration.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 每次独立调用模型，完整注入指令文档，只接受契约所描述的 JSON。 */
@Component
public class ReviewModelClient {

    private final ChatModel model;
    private final ObjectMapper json;

    public ReviewModelClient(ChatModel model, ObjectMapper json) {
        this.model = model;
        this.json = json;
    }

    public <T> T call(String skill, String task, Object input, Class<T> responseType) {
        String instructions;
        String request;
        try {
            instructions = new ClassPathResource("skills/" + skill + "/SKILL.md")
                    .getContentAsString(StandardCharsets.UTF_8);
            request = json.writeValueAsString(Map.of("task", task, "input", input));
        }
        catch (IOException exception) {
            throw new IllegalStateException("无法加载审查指令或构造模型输入");
        }
        ChatResponse response = model.call(new Prompt(List.of(new SystemMessage(instructions), new UserMessage(request))));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("审查模型返回了空内容");
        }
        try {
            T parsed = json.readerFor(responseType)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(response.getResult().getOutput().getText());
            if (parsed == null) { throw new IllegalStateException("审查模型返回了空 JSON"); }
            return parsed;
        }
        catch (JsonProcessingException exception) {
            // 解析异常可能带上模型原文，审查记录与日志只保留原因。
            throw new IllegalStateException("审查模型返回的 JSON 不符合约定");
        }
    }
}
