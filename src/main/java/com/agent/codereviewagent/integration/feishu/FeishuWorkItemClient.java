package com.agent.codereviewagent.integration.feishu;

import com.agent.codereviewagent.config.FeishuProjectProperties;
import com.agent.codereviewagent.service.WorkItemClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 用插件凭据和专用账号，在配置空间的全部工作项类型中按 ID 查找。 */
@Component
public class FeishuWorkItemClient implements WorkItemClient {

    private final FeishuProjectProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
    private String cachedToken;
    private Instant refreshAt = Instant.EPOCH;

    public FeishuWorkItemClient(FeishuProjectProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Optional<String> readPlainText(String workItemId) {
        requireConfiguration();
        long id;
        try { id = Long.parseLong(workItemId); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("工作项 ID 格式不正确", exception); }
        String token = token();
        String space = "/open_api/" + encode(properties.getProjectKey()) + "/work_item";
        List<WorkItemType> types = request("GET", space + "/all-types", null, token,
                new TypeReference<ApiResponse<List<WorkItemType>>>() { });
        List<String> typeKeys = types.stream().map(WorkItemType::typeKey).toList();
        if (typeKeys.stream().anyMatch(FeishuWorkItemClient::blank)) {
            throw new IllegalStateException("飞书项目工作项类型响应格式不正确");
        }
        if (typeKeys.isEmpty()) { return Optional.empty(); }
        List<WorkItem> items = request("POST", space + "/filter", Map.of(
                "work_item_ids", List.of(id),
                "work_item_type_keys", typeKeys,
                "page_num", 1,
                "page_size", 200,
                "expand", Map.of("need_multi_text", true)), token,
                new TypeReference<ApiResponse<List<WorkItem>>>() { });
        return items.stream().filter(item -> item.id() != null && item.id() == id)
                .findFirst().flatMap(FeishuWorkItemClient::description);
    }

    private static Optional<String> description(WorkItem item) {
        if (item.multiTexts() != null) {
            for (MultiText field : item.multiTexts()) {
                if ("description".equals(field.fieldKey()) && field.fieldValue() != null) {
                    return Optional.ofNullable(field.fieldValue().docText()).filter(text -> !text.isBlank());
                }
            }
        }
        if (item.fields() != null) {
            for (Field field : item.fields()) {
                if ("description".equals(field.fieldKey()) && field.fieldValue() instanceof String text) {
                    return Optional.of(text).filter(value -> !value.isBlank());
                }
            }
        }
        return Optional.empty();
    }

    /** 插件 token 两小时有效；距过期五分钟时重新换取，不缓存工作项正文。 */
    private synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(refreshAt)) { return cachedToken; }
        Token data = request("POST", "/open_api/authen/plugin_token", Map.of(
                "plugin_id", properties.getPluginId(), "plugin_secret", properties.getPluginSecret()), null,
                new TypeReference<ApiResponse<Token>>() { });
        if (blank(data.token()) || data.expireTime() == null || data.expireTime() <= 0) {
            throw new IllegalStateException("飞书项目插件凭据响应格式不正确");
        }
        cachedToken = data.token();
        refreshAt = Instant.now().plusSeconds(Math.max(0, data.expireTime() - 300));
        return cachedToken;
    }

    private <T> T request(String method, String path, Object body, String token,
                          TypeReference<ApiResponse<T>> responseType) {
        try {
            String base = properties.getBaseUrl().replaceAll("/+$", "");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(properties.getRequestTimeout()).header("Content-Type", "application/json");
            if (token != null) {
                request.header("X-PLUGIN-TOKEN", token).header("X-USER-KEY", properties.getUserKey())
                        .header("x-auth-mode", "1");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("飞书项目接口请求失败，HTTP " + response.statusCode());
            }
            ApiResponse<T> result = json.readValue(response.body(), responseType);
            if (result == null || result.errorCode() == null) {
                throw new IllegalStateException("飞书项目响应缺少错误码");
            }
            if (result.errorCode() != 0) {
                String reason = result.errorCode() == 10302 ? "专用账号不可用或已失去权限" : "接口调用失败";
                throw new IllegalStateException("飞书项目" + reason + "，错误码 " + result.errorCode());
            }
            if (result.data() == null) { throw new IllegalStateException("飞书项目响应缺少数据"); }
            return result.data();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书项目请求被中断", exception);
        }
        catch (IOException exception) { throw new IllegalStateException("飞书项目请求或响应读取失败", exception); }
    }

    private void requireConfiguration() {
        if (blank(properties.getBaseUrl()) || blank(properties.getProjectKey()) || blank(properties.getPluginId())
                || blank(properties.getPluginSecret()) || blank(properties.getUserKey())) {
            throw new IllegalStateException("飞书项目空间、插件凭据或专用账号尚未配置");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ApiResponse<T>(@JsonProperty("err_code") Integer errorCode, T data) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Token(String token, @JsonProperty("expire_time") Integer expireTime) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkItemType(@JsonProperty("type_key") String typeKey) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkItem(Long id, @JsonProperty("multi_texts") List<MultiText> multiTexts, List<Field> fields) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MultiText(@JsonProperty("field_key") String fieldKey, @JsonProperty("field_value") RichText fieldValue) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RichText(@JsonProperty("doc_text") String docText) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Field(@JsonProperty("field_key") String fieldKey, @JsonProperty("field_value") Object fieldValue) { }
}
