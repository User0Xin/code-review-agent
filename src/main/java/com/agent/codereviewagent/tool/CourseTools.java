package com.agent.codereviewagent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

@Component
public class CourseTools {

    private static final Map<String, String> COURSES = Map.of(
            "chatclient", "ChatClient 是对 ChatModel 的调用封装：系统提示词、单轮问答、流式输出都从这里开始。它自己不会决定要不要查外部数据。",
            "reactagent", "ReactAgent 按推理-行动循环工作：模型先决定是否调用工具，框架执行工具后再把结果交回模型，直到给出最终回答。",
            "memory", "MemorySaver 把一次对话保存在进程内存里。相同 threadId 会带上历史消息；应用重启后记忆消失。"
    );

    @Tool(description = "按关键词查询 Spring AI Alibaba 课程说明。可用关键词：chatclient、react-agent、memory。")
    public String lookupCourse(@ToolParam(description = "课程关键词，例如 react-agent") String keyword) {
        String normalized = normalize(keyword);
        if (normalized.isEmpty() || "all".equals(normalized) || "list".equals(normalized)) {
            return String.join("\n", COURSES.values());
        }
        return COURSES.entrySet().stream()
                .filter(entry -> normalized.contains(entry.getKey()) || entry.getKey().contains(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("没有找到课程。可用关键词：chatclient、react-agent、memory。");
    }

    @Tool(description = "获取服务器当前时间，ISO-8601 格式。用户询问现在几点时调用。")
    public String currentTime() {
        return OffsetDateTime.now().toString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

}
