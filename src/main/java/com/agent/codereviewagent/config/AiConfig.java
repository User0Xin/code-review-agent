package com.agent.codereviewagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/** 配置评审模型请求使用的 HTTP 客户端，使其兼容本地模型网关。 */
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
                // 删除字段后，请求头里的长度也要与实际发送的字节数一致。
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

}
