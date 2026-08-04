package com.huawei.finance.gateway;

import com.huawei.finance.stability.Api;
import java.util.List;

/**
 * 带工具的 Chat 返回。
 *
 * @param content   模型的文本输出，决定调工具时通常为空
 * @param toolCalls 模型请求的工具调用，为空表示它认为可以收尾了
 */
@Api
public record ToolChatReply(String content, List<ToolCallRequest> toolCalls) {

    public ToolChatReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }

    /**
     * 模型请求的一次工具调用。
     *
     * <p>{@code arguments} 保留**原始 JSON 字符串**而不是解析成 Map：模型给出的参数经常不
     * 合 schema，在这里解析失败就只能抛，而调用方其实需要拿到原文去判断是重问还是丢弃。
     *
     * @param id        调用标识，回填结果时要原样带回
     * @param name      工具名
     * @param arguments 参数的 JSON 原文
     */
    @Api
    public record ToolCallRequest(String id, String name, String arguments) {
    }
}
