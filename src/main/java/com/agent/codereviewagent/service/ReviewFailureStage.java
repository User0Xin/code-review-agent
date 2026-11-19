package com.agent.codereviewagent.service;

import java.util.Map;
import java.util.Set;

/** 对外失败说明只接受本服务生成的固定语义，不透传历史异常文本。 */
public final class ReviewFailureStage {
    private ReviewFailureStage() {}
    public static final String UNKNOWN_REASON = "失败原因未记录，请核对远端评论与服务状态";
    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("diff 读取失败", "DIFF"), Map.entry("diff 正文读取失败", "DIFF"),
            Map.entry("合并请求的 diff 尚未就绪", "DIFF"), Map.entry("已有讨论读取失败", "HISTORY"),
            Map.entry("编码规范读取失败", "STANDARDS"), Map.entry("spec 读取失败", "SPEC"),
            Map.entry("模型审查失败", "MODEL"), Map.entry("提交状态核对失败", "VERSION_CHECK"),
            Map.entry("审查期间提交已变化", "VERSION_CHECK"), Map.entry("评论写回失败", "PUBLISH"),
            Map.entry("讨论状态更新失败", "DISCUSSION_UPDATE"), Map.entry("审查执行失败", "EXECUTION"),
            Map.entry("审查队列已满，请稍后重试", "QUEUE"),
            Map.entry("服务中断，请核对远端评论后重新发起评审", "INTERRUPTED"),
            Map.entry("审查执行中断，请核对仓库配置和服务端日志", "EXECUTION"),
            Map.entry("等待事件指定版本的 diff 超时，请核对提交后重新发起评审", "DIFF"),
            Map.entry("无法读取事件指定版本的 diff，请核对平台连接后重试", "DIFF"));
    private static final Set<String> STAGES = Set.of("QUEUE", "DIFF", "HISTORY", "STANDARDS", "SPEC",
            "MODEL", "VERSION_CHECK", "PUBLISH", "DISCUSSION_UPDATE", "EXECUTION", "INTERRUPTED", "UNKNOWN");

    public static String fromReason(String reason) {
        if (reason != null && reason.matches("有 [0-9]{1,9} 条没能写回")) { return "PUBLISH"; }
        return REASONS.getOrDefault(reason == null ? "" : reason, "UNKNOWN");
    }

    public static String safeStage(String stage) { return stage != null && STAGES.contains(stage) ? stage : "UNKNOWN"; }

    public static String safeReason(String reason) {
        return "UNKNOWN".equals(fromReason(reason)) ? UNKNOWN_REASON : reason;
    }

    public static String safeMessage(String message) {
        if (message == null) { return null; }
        if (message.matches("审查完成，新增 [0-9]{1,9} 条评论（未选中 spec）|审查完成，新增 [0-9]{1,9} 条评论")) {
            return message;
        }
        return safeReason(message);
    }
}
