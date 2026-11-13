package com.agent.codereviewagent.integration.github;

import com.agent.codereviewagent.config.GithubProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 只使用配置中的 API 地址，不跟随重定向，也不将远端错误正文写入异常。 */
final class GithubApi {
    private final GithubProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1).build();

    GithubApi(GithubProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    ObjectNode object() { return json.createObjectNode(); }

    String repository(long repositoryId) {
        if (repositoryId <= 0 || repositoryId != properties.getRepositoryId()) {
            throw new IllegalStateException("GitHub 仓库编号与配置不符");
        }
        String name = properties.getRepository();
        if (name == null || !name.matches("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("GitHub 仓库配置应为 owner/repo");
        }
        return name;
    }

    static String segment(String value) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("GitHub 路径片段无效");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String path(String value) {
        return Arrays.stream(value.split("/", -1)).map(GithubApi::segment).collect(Collectors.joining("/"));
    }

    JsonNode get(String path) { return send("GET", path, null); }

    String raw(String path) { return request("GET", path, "application/vnd.github.raw+json", null); }

    JsonNode send(String method, String path, ObjectNode payload) {
        String response = request(method, path, "application/vnd.github+json", payload);
        try {
            return response.isBlank() ? json.nullNode() : json.readTree(response);
        } catch (IOException exception) {
            throw new IllegalStateException("GitHub 响应不是有效 JSON", exception);
        }
    }

    JsonNode graphql(String query, ObjectNode variables) {
        ObjectNode payload = object().put("query", query);
        payload.set("variables", variables);
        JsonNode result = send("POST", "/graphql", payload);
        if (result.hasNonNull("errors") || !result.path("data").isObject()) {
            throw new IllegalStateException("GitHub GraphQL 请求未成功");
        }
        return result.get("data");
    }

    private String request(String method, String path, String accept, ObjectNode payload) {
        String token = properties.getToken();
        if (token == null || token.isBlank()) { throw new IllegalStateException("未设置 GitHub token"); }
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        if (path.equals("/graphql") && base.endsWith("/api/v3")) {
            base = base.substring(0, base.length() - "/v3".length());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(properties.getRequestTimeout())
                .header("Authorization", "Bearer " + token).header("Accept", accept)
                .header("Content-Type", "application/json").header("X-GitHub-Api-Version", "2022-11-28")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new RequestFailure(response.statusCode(), response.body());
            }
            return response.body();
        } catch (IOException exception) {
            throw new IllegalStateException("GitHub 连接失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub 请求被中断", exception);
        }
    }

    static final class RequestFailure extends IllegalStateException {
        private final int status;
        private final String details;
        RequestFailure(int status, String details) {
            super("GitHub 接口请求失败，HTTP " + status);
            this.status = status;
            this.details = details;
        }
        boolean rejectedLine() {
            if (status != 422) { return false; }
            String error = details.toLowerCase(java.util.Locale.ROOT);
            return error.contains("diff hunk") || error.contains("part of the diff")
                    || error.contains("\"field\":\"line\"") || error.contains("\"field\":\"path\"");
        }
    }
}
