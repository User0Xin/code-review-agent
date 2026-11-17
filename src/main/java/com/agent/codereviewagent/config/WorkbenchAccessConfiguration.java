package com.agent.codereviewagent.config;

import com.agent.codereviewagent.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 工作台与平台 webhook 独立授权；部署没有设置管理凭据时，管理接口保持关闭。 */
@Configuration(proxyBeanMethods = false)
public class WorkbenchAccessConfiguration implements WebMvcConfigurer {
    private final String token;
    private final ObjectMapper json;

    public WorkbenchAccessConfiguration(@Value("${review.workbench.token:${REVIEW_WORKBENCH_TOKEN:}}") String token,
                                        ObjectMapper json) {
        this.token = token;
        this.json = json;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                String header = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (!token.isBlank() && header != null && header.startsWith("Bearer ")
                        && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        header.substring(7).getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
                int code = token.isBlank() ? 503 : 401;
                response.setStatus(code);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(), Result.error(code, token.isBlank()
                        ? "请在服务端设置 REVIEW_WORKBENCH_TOKEN 后重启" : "请使用工作台管理口令解锁"));
                return false;
            }
        }).addPathPatterns("/api/**");
    }
}
