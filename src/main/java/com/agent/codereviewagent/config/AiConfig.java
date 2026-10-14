package com.agent.codereviewagent.config;

import com.agent.codereviewagent.tool.CourseTools;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AiConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * JDK HttpClient 默认偏好 HTTP/2，访问 http 地址时会发 h2c 升级。
     * 很多本地 OpenAI 兼容网关遇到升级请求会直接断开，所以这里固定 HTTP/1.1。
     * 同时去掉 Codex 网关会拒绝的 stream=false、extra_body、temperature。
     */
    @Bean
    RestClientCustomizer http11RestClientCustomizer() {
        return builder -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(120));
            builder.requestFactory(requestFactory);
            builder.requestInterceptor((request, body, execution) -> {
                byte[] sanitized = sanitizeChatBody(body);
                if (sanitized == null) {
                    sanitized = new byte[0];
                }
                request.getHeaders().setContentLength(sanitized.length);
                return execution.execute(request, sanitized);
            });
        };
    }

    static byte[] sanitizeChatBody(byte[] body) {
        if (body == null || body.length == 0) {
            return body;
        }
        try {
            JsonNode tree = JSON.readTree(body);
            if (!(tree instanceof ObjectNode objectNode)) {
                return body;
            }
            JsonNode stream = objectNode.get("stream");
            if (stream != null && stream.isBoolean() && !stream.booleanValue()) {
                objectNode.remove("stream");
            }
            objectNode.remove("extra_body");
            objectNode.remove("temperature");
            return JSON.writeValueAsBytes(objectNode);
        }
        catch (Exception ignored) {
            return body;
        }
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是简洁的中文助手。直接回答用户的问题。")
                .build();
    }

    @Bean
    ReactAgent courseAgent(ChatModel chatModel,
                           CourseTools courseTools,
                           @Value("${demo.tools-enabled:false}") boolean toolsEnabled) {
        var builder = ReactAgent.builder()
                .name("course_agent")
                .description("回答 Spring AI Alibaba 课程问题，需要事实时调用工具")
                .model(chatModel)
                .saver(new MemorySaver())
                .hooks(ModelCallLimitHook.builder().runLimit(8).build())
                .enableLogging(true);
        if (toolsEnabled) {
            builder.systemPrompt("""
                            你是 Spring AI Alibaba 课程顾问，用中文回答。
                            查询课程说明或当前时间时必须调用工具，不要编造课表或时间。
                            记住同一会话里用户说过的名字和偏好。
                            """)
                    .methodTools(courseTools);
        }
        else {
            builder.systemPrompt("你是简洁的中文助手。直接回答，并记住同一会话里用户说过的内容。");
        }
        return builder.build();
    }

}
